package ru.study.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.study.mailadapter.api.MailAdapter;
import ru.study.mailadapter.model.AccountConfig;
import ru.study.mailadapter.model.OutgoingAttachment;
import ru.study.mailadapter.model.RawOutgoingMail;
import ru.study.core.dto.MessageSummaryDTO;
import ru.study.core.dto.KeyDTO;
import ru.study.core.dto.MessageDetailDTO;
import ru.study.core.exception.CoreException;
import ru.study.core.exception.NotFoundException;
import ru.study.persistence.entity.MessageEntity;
import ru.study.persistence.entity.AttachmentEntity;
import ru.study.persistence.entity.MessageWrappedKeyEntity;
import ru.study.persistence.mapper.MessageMapper;
import ru.study.persistence.util.EntityManagerFactoryProvider;
import ru.study.persistence.util.MapperUtils;
import ru.study.persistence.repository.impl.AccountRepositoryImpl;
import ru.study.persistence.repository.impl.MessageRepositoryImpl;
import ru.study.persistence.repository.impl.AttachmentRepositoryImpl;
import ru.study.persistence.repository.impl.FolderRepositoryImpl;
import ru.study.persistence.repository.impl.KeyRepositoryImpl;
import ru.study.persistence.repository.impl.MessageWrappedKeyRepositoryImpl;
import ru.study.persistence.repository.api.AccountRepository;
import ru.study.persistence.repository.api.MessageRepository;
import ru.study.persistence.repository.api.AttachmentRepository;
import ru.study.persistence.repository.api.FolderRepository;
import ru.study.persistence.repository.api.KeyRepository;
import ru.study.persistence.repository.api.MessageWrappedKeyRepository;
import ru.study.core.model.AttachmentReference;
import ru.study.core.model.EmailAddress;
import ru.study.service.api.MailService;
import ru.study.service.api.AccountService;
import ru.study.service.api.AttachmentService;
import ru.study.service.api.KeyManagementService;
import ru.study.service.api.MasterPasswordService;
import ru.study.service.api.NotificationService;
import ru.study.service.client.KeyServerClient;
import ru.study.service.dto.OutgoingAttachmentDTO;
import ru.study.service.dto.SendMessageDTO;
import ru.study.service.dto.SendResultDTO;
import ru.study.crypto.provider.CryptoProviderFactory;
import ru.study.crypto.api.SymmetricCipher;
import ru.study.crypto.api.AsymmetricCipher;
import ru.study.crypto.api.Signer;
import ru.study.crypto.model.EncryptedBlob;
import ru.study.crypto.util.EncryptedBlobCodec;
import ru.study.crypto.util.PemUtils;
import ru.study.core.event.NewMessageEvent;
import ru.study.core.event.bus.EventBus;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.PrivateKey;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Refactored MailServiceImpl (C1/C2):
 * - no long-lived EM nor repositories as fields
 * - perform network send before DB tx
 * - persist MessageWrappedKeyEntity per recipient
 * - decrypt flow in getMessage uses MessageWrappedKeyRepository + KeyManagementService
 * - sendAsync uses dedicated executor
 * - logging added
 */
public class MailServiceImpl implements MailService {

    private static final Logger log = LoggerFactory.getLogger(MailServiceImpl.class);

    private final AccountService accountService;
    private final MailAdapter mailAdapter;
    private final AttachmentService attachmentService;
    private final KeyServerClient keyServerClient;
    private final CryptoProviderFactory cryptoProviderFactory;
    private final KeyManagementService keyManagementService;
    private final MasterPasswordService masterPasswordService;
    private final NotificationService notificationService;
    private final EventBus eventBus;

    // async sender
    private final ExecutorService sendExecutor;
    private final int DEFAULT_SEND_THREADS = 4;

    public MailServiceImpl(AccountService accountService,
                           MailAdapter mailAdapter,
                           AttachmentService attachmentService,
                           KeyServerClient keyServerClient,
                           CryptoProviderFactory cryptoProviderFactory,
                           KeyManagementService keyManagementService,
                           MasterPasswordService masterPasswordService,
                           NotificationService notificationService,
                           EventBus eventBus) {
        this.accountService = accountService;
        this.mailAdapter = mailAdapter;
        this.attachmentService = attachmentService;
        this.keyServerClient = keyServerClient;
        this.cryptoProviderFactory = cryptoProviderFactory;
        this.keyManagementService = keyManagementService;
        this.masterPasswordService = masterPasswordService;
        this.notificationService = notificationService;
        this.eventBus = eventBus;

        this.sendExecutor = Executors.newFixedThreadPool(DEFAULT_SEND_THREADS, r -> {
            Thread t = new Thread(r, "mail-send-executor");
            t.setDaemon(true);
            return t;
        });
    }

    // call on app shutdown
    public void shutdown() {
        try {
            sendExecutor.shutdownNow();
            log.info("MailService shutdown completed");
        } catch (Exception e) {
            log.warn("Failed to shutdown sendExecutor", e);
        }
    }

    @Override
    public SendResultDTO send(SendMessageDTO dto, Long accountId, boolean encrypt, boolean sign) throws CoreException {
        log.info("Send start: accountId={}, subject='{}', encrypt={}, sign={}, attachments={}",
                accountId, dto == null ? null : dto.getSubject(), encrypt, sign,
                dto == null || dto.getAttachments() == null ? 0 : dto.getAttachments().size());

        // 1) get account config (no EM)
        AccountConfig cfg = accountService.getAccountConfig(accountId);
        if (cfg == null) {
            log.error("Account config not found for accountId: {}", accountId);
            throw new NotFoundException("Account config not found: " + accountId);
        }

        // prepare outgoing attachments and a map for persist paths (either original file or temp)
        List<OutgoingAttachment> outgoingAttachments = new ArrayList<>();
        List<InputStream> streamsToClose = new ArrayList<>(); // streams we opened for sending
        Map<ru.study.service.dto.OutgoingAttachmentDTO, java.nio.file.Path> persistPath = new LinkedHashMap<>();

        try {
            if (dto.getAttachments() != null) {
                log.debug("Processing {} attachments", dto.getAttachments().size());
                for (ru.study.service.dto.OutgoingAttachmentDTO a : dto.getAttachments()) {
                    if (a.getFilePath() != null) {
                        // file-backed: open file stream for sending, remember path for persist
                        java.nio.file.Path p = java.nio.file.Paths.get(a.getFilePath());
                        try {
                            InputStream sendIn = java.nio.file.Files.newInputStream(p, java.nio.file.StandardOpenOption.READ);
                            outgoingAttachments.add(new OutgoingAttachment(a.getFileName(), a.getContentType(), sendIn));
                            streamsToClose.add(sendIn);
                            persistPath.put(a, p); // original file will be used for saving later
                            log.debug("Using file-backed attachment for send: {}", p);
                        } catch (IOException e) {
                            log.error("Failed to open file attachment: {}", p, e);
                            throw new CoreException("Failed to open attachment file: " + p, e);
                        }
                    } else {
                        // ephemeral stream: copy to temp file so we can both send and then persist
                        try {
                            java.nio.file.Path tmp = java.nio.file.Files.createTempFile("att-send-", ".tmp");
                            try (InputStream src = a.getInput();
                                java.io.OutputStream dst = java.nio.file.Files.newOutputStream(tmp, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
                                byte[] buf = new byte[8192];
                                int r;
                                while ((r = src.read(buf)) != -1) dst.write(buf, 0, r);
                                dst.flush();
                            }
                            InputStream sendIn = java.nio.file.Files.newInputStream(tmp, java.nio.file.StandardOpenOption.READ);
                            outgoingAttachments.add(new OutgoingAttachment(a.getFileName(), a.getContentType(), sendIn));
                            streamsToClose.add(sendIn);
                            persistPath.put(a, tmp); // temp will be used for saving and then deleted
                            log.debug("Copied ephemeral attachment to temp for send: {}", tmp);
                        } catch (IOException e) {
                            log.error("Failed to create temp file for ephemeral attachment", e);
                            throw new CoreException("Failed to process attachment", e);
                        }
                    }
                }
            }

            RawOutgoingMail outgoing = new RawOutgoingMail(
                    dto.getFrom(),
                    dto.getTo(),
                    dto.getCc(),
                    dto.getBcc(),
                    dto.getSubject(),
                    dto.getBody(),
                    dto.isHtml(),
                    outgoingAttachments
            );

            // --- crypto / signing same as before ---
            byte[] wrappedKeyBlob = null;
            byte[] encryptedBodyBlob = null;
            byte[] bodyIv = null;
            byte[] signatureBlob = null;

            try {
                if (encrypt) {
                    try {
                        log.debug("Starting encryption for message");
                        SymmetricCipher sym = cryptoProviderFactory.getSymmetricCipher("DES-ECB");
                        byte[] symKey = sym.generateKey();

                        EncryptedBlob bodyEnc = sym.encrypt(symKey, dto.getBody() == null ? new byte[0] : dto.getBody().getBytes(StandardCharsets.UTF_8));
                        encryptedBodyBlob = EncryptedBlobCodec.encodeToBytes(bodyEnc);
                        bodyIv = bodyEnc.iv();

                        String firstRecipient = null;
                        if (dto.getTo() != null && !dto.getTo().isEmpty()) firstRecipient = dto.getTo().get(0);
                        Optional<KeyDTO> recipientKey = Optional.empty();
                        if (firstRecipient != null) {
                            log.debug("Looking up public key for recipient: {}", firstRecipient);
                            recipientKey = keyServerClient.findKeyByEmail(firstRecipient);
                        }

                        if (recipientKey.isPresent()) {
                            String pem = recipientKey.get().publicKeyPem();
                            PublicKey pub = PemUtils.publicKeyFromPem(pem);
                            AsymmetricCipher asym = cryptoProviderFactory.getAsymmetricCipher("RSA");
                            wrappedKeyBlob = asym.encrypt(pub, symKey);
                            log.debug("Encryption completed successfully for recipient: {}", firstRecipient);
                        } else {
                            log.warn("No public key found for recipient(s). Sending unencrypted.");
                            notificationService.notifyInfo("No public key for recipient(s). Sending unencrypted.");
                            encryptedBodyBlob = null;
                            bodyIv = null;
                            wrappedKeyBlob = null;
                        }
                    } catch (Exception ex) {
                        log.error("Encryption failed, sending plaintext", ex);
                        notificationService.notifyError("Encryption failed, sending plaintext", ex);
                        encryptedBodyBlob = null;
                        bodyIv = null;
                        wrappedKeyBlob = null;
                    }
                }

                if (sign) {
                    try {
                        log.debug("Starting signing process");
                        try (EntityManager em = EntityManagerFactoryProvider.createEntityManager()) {
                            KeyRepository keyRepository = new KeyRepositoryImpl(em);
                            var primaryOpt = keyRepository.findPrimaryByAccountId(accountId);
                            if (primaryOpt.isPresent()) {
                                var primary = primaryOpt.get();
                                Long keyId = primary.getId();
                                Optional<char[]> maybeMaster = masterPasswordService.getCurrentMasterPassword();
                                if (maybeMaster.isPresent()) {
                                    char[] mp = maybeMaster.get();
                                    byte[] pkcs8 = keyManagementService.decryptPrivateKey(keyId, mp);
                                    try {
                                        PrivateKey priv = PemUtils.privateKeyFromPkcs8(pkcs8);
                                        Signer signer = cryptoProviderFactory.getSigner("MD5withRSA");
                                        byte[] dataToSign = dto.getBody() == null ? new byte[0] : dto.getBody().getBytes(StandardCharsets.UTF_8);
                                        signatureBlob = signer.sign(priv, dataToSign);
                                        log.debug("Message signed successfully with key ID: {}", keyId);
                                    } finally {
                                        Arrays.fill(pkcs8, (byte) 0);
                                    }
                                } else {
                                    log.warn("Master password not available — cannot sign.");
                                    notificationService.notifyInfo("Master password not available — cannot sign.");
                                }
                            } else {
                                log.warn("No primary private key for account — skipping signature.");
                                notificationService.notifyInfo("No primary private key for account — skipping signature.");
                            }
                        }
                    } catch (Exception ex) {
                        log.error("Signing failed, continuing without signature", ex);
                        notificationService.notifyError("Signing failed, continuing without signature", ex);
                        signatureBlob = null;
                    }
                }

                // 4) connect adapter and send (network IO outside DB tx)
                try {
                    log.debug("Connecting to mail adapter for sending");
                    mailAdapter.connect(cfg);
                    mailAdapter.send(outgoing);
                    log.info("Mail sent via adapter for accountId={}, subject='{}'", accountId, dto.getSubject());
                } catch (Exception ex) {
                    log.error("Failed to send message via SMTP", ex);
                    notificationService.notifyError("Failed to send message via SMTP", ex);
                    throw new CoreException("Failed to send message via SMTP: " + ex.getMessage(), ex);
                } finally {
                    try {
                        mailAdapter.disconnect();
                        log.debug("Disconnected from mail adapter");
                    } catch (Exception e) {
                        log.warn("Error disconnecting from mail adapter", e);
                    }
                }

                // close send streams (they are no longer needed)
                log.debug("Closing {} send streams", streamsToClose.size());
                for (InputStream is : streamsToClose) {
                    try {
                        is.close();
                    } catch (Exception e) {
                        log.warn("Error closing send stream", e);
                    }
                }
                streamsToClose.clear();

                // 5) Persist message + wrapped keys + attachments in short DB tx
                EntityManager em = EntityManagerFactoryProvider.createEntityManager();
                try {
                    MessageRepository messageRepository = new MessageRepositoryImpl(em);
                    FolderRepository folderRepository = new FolderRepositoryImpl(em);
                    AttachmentRepository attachmentRepository = new AttachmentRepositoryImpl(em);
                    MessageWrappedKeyRepository wrappedRepo = new MessageWrappedKeyRepositoryImpl(em);
                    EntityTransaction tx = em.getTransaction();
                    try {
                        tx.begin();
                        log.debug("Beginning transaction to persist sent message");

                        MessageEntity me = new MessageEntity();
                        me.setAccountId(accountId);
                        folderRepository.findByAccountAndServerName(accountId, "Sent").ifPresent(me::setFolder);
                        me.setSubject(dto.getSubject());
                        me.setSender(dto.getFrom());
                        me.setRecipients(MapperUtils.emailsToCsv(
                                (dto.getTo() == null) ? List.of() : dto.getTo().stream().map(EmailAddress::new).collect(Collectors.toList())
                        ));
                        me.setCc(MapperUtils.emailsToCsv(
                                (dto.getCc() == null) ? List.of() : dto.getCc().stream().map(EmailAddress::new).collect(Collectors.toList())
                        ));
                        me.setSentDate(java.time.OffsetDateTime.now());
                        me.setIsSeen(Boolean.TRUE);
                        me.setIsEncrypted(encryptedBodyBlob != null);
                        me.setEncryptedBodyBlob(encryptedBodyBlob);
                        me.setBodyIv(bodyIv);
                        me.setSignatureBlob(signatureBlob);

                        MessageEntity saved = messageRepository.save(me);
                        log.debug("Persisted message entity with ID: {}", saved.getId());

                        // Wrapped keys
                        if (wrappedKeyBlob != null) {
                            Set<String> recipients = new LinkedHashSet<>();
                            if (dto.getTo() != null) recipients.addAll(dto.getTo());
                            if (dto.getCc() != null) recipients.addAll(dto.getCc());
                            if (dto.getBcc() != null) recipients.addAll(dto.getBcc());
                            log.debug("Persisting wrapped keys for {} recipients", recipients.size());
                            for (String r : recipients) {
                                MessageWrappedKeyEntity wk = new MessageWrappedKeyEntity();
                                wk.setMessage(saved);
                                wk.setRecipient(r);
                                wk.setWrappedBlob(wrappedKeyBlob);
                                wrappedRepo.save(wk);
                            }
                        }

                        // -----------------------------
                        // Persist attachments using the same EntityManager/transaction (atomic)
                        // -----------------------------
                        if (!persistPath.isEmpty()) {
                            log.debug("Persisting {} attachments in same transaction", persistPath.size());
                            // local attachments dir (mirror of AttachmentServiceImpl default)
                            final java.nio.file.Path attachmentsDir = java.nio.file.Paths.get("./data/attachments");
                            try {
                                java.nio.file.Files.createDirectories(attachmentsDir);
                            } catch (Exception ignored) {}

                            final long ATTACH_DB_THRESHOLD = 5_242_880L; // 5MB threshold (keep in sync with AttachmentServiceImpl)

                            for (var entry : persistPath.entrySet()) {
                                var aDto = entry.getKey();
                                var path = entry.getValue();
                                try {
                                    long size = java.nio.file.Files.size(path);

                                    AttachmentEntity ae = new AttachmentEntity();
                                    ae.setMessage(saved);
                                    ae.setFilename(aDto.getFileName() == null ? "unknown" : aDto.getFileName());
                                    ae.setContentType(aDto.getContentType());
                                    ae.setSize(size);

                                    if (size <= ATTACH_DB_THRESHOLD) {
                                        // store in DB
                                        try (InputStream saveIn = java.nio.file.Files.newInputStream(path, java.nio.file.StandardOpenOption.READ)) {
                                            byte[] data = saveIn.readAllBytes(); // caution: large attachments -> memory
                                            ae.setEncryptedBlob(data);
                                            ae.setIv(null);
                                            ae.setFilePath(null);
                                            attachmentRepository.save(ae); // uses same em/tx
                                            log.debug("Persisted attachment to DB: {}", aDto.getFileName());
                                        }
                                        // if this was a temp file we created earlier, delete it
                                        if (aDto.getFilePath() == null) {
                                            try { java.nio.file.Files.deleteIfExists(path); } catch (Exception e) { log.warn("Failed to delete temp file: {}", path, e); }
                                        }
                                    } else {
                                        // store on FS: move file to final storage dir and save path in DB
                                        String rawName = ae.getFilename() == null ? "unknown" : ae.getFilename();
                                        String sanitized = rawName.replaceAll("[^a-zA-Z0-9._-]", "_");
                                        String safeName = UUID.randomUUID() + "_" + sanitized;
                                        java.nio.file.Path finalPath = attachmentsDir.resolve(safeName);
                                        try {
                                            try {
                                                java.nio.file.Files.move(path, finalPath, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                                            } catch (java.nio.file.AtomicMoveNotSupportedException amnse) {
                                                java.nio.file.Files.move(path, finalPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                                            }
                                        } catch (IOException ioe) {
                                            log.warn("Failed to move attachment file to final path, trying copy: {}", ioe.getMessage());
                                            // fallback to copy
                                            java.nio.file.Files.copy(path, finalPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                                            try { java.nio.file.Files.deleteIfExists(path); } catch (IOException ignored) {}
                                        }
                                        ae.setFilePath(finalPath.toString());
                                        ae.setEncryptedBlob(null);
                                        ae.setIv(null);

                                        attachmentRepository.save(ae); // persist entity with path
                                        log.debug("Persisted attachment file: {} -> {}", aDto.getFileName(), finalPath);
                                    }

                                } catch (Exception ex) {
                                    // keep processing other attachments, but log & notify
                                    log.warn("Failed to save outgoing attachment '{}' : {}", aDto.getFileName(), ex.getMessage());
                                }
                            }
                        }

                        tx.commit();
                        log.debug("Transaction committed successfully");

                        MessageSummaryDTO summary = MessageMapper.toSummaryDto(MessageMapper.toDomain(saved));
                        eventBus.publish(new NewMessageEvent(summary));

                        log.info("Send completed successfully for message ID: {}", saved.getId());
                        return SendResultDTO.builder().success(true).messageId(String.valueOf(saved.getId())).error(null).build();
                    } catch (Exception e) {
                        if (tx.isActive()) {
                            tx.rollback();
                            log.debug("Transaction rolled back due to error");
                        }
                        log.error("Failed to persist sent message", e);
                        notificationService.notifyError("Failed to persist sent message", e);
                        throw new CoreException("Failed to persist sent message: " + e.getMessage(), e);
                    } finally {
                        if (em.isOpen()) {
                            em.close();
                            log.debug("EntityManager closed");
                        }
                    }
                } catch (Exception e) {
                    log.error("Persist after send failed", e);
                    notificationService.notifyError("Persist after send failed", e);
                    throw new CoreException("Persist after send failed: " + e.getMessage(), e);
                }

            } catch (CoreException ce) {
                log.error("CoreException during send process", ce);
                throw ce;
            } catch (Exception ex) {
                log.error("Unexpected exception during send process", ex);
                notificationService.notifyError("Send failed", ex);
                throw new CoreException("Send failed: " + ex.getMessage(), ex);
            }

        } finally {
            // ensure any send streams still open are closed
            log.debug("Final cleanup - closing {} remaining streams", streamsToClose.size());
            for (InputStream is : streamsToClose) {
                try {
                    is.close();
                } catch (Exception e) {
                    log.warn("Error closing stream during final cleanup", e);
                }
            }
            streamsToClose.clear();

            // Clean up any temp files in case of early exit
            for (var entry : persistPath.entrySet()) {
                var a = entry.getKey();
                var path = entry.getValue();
                if (a.getFilePath() == null) {
                    try {
                        java.nio.file.Files.deleteIfExists(path);
                        log.debug("Cleaned up temp file in final block: {}", path);
                    } catch (Exception e) {
                        log.warn("Failed to clean up temp file in final block: {}", path, e);
                    }
                }
            }
        }
    }

    
    @Override
    public CompletableFuture<SendResultDTO> sendAsync(SendMessageDTO dto, Long accountId, boolean encrypt, boolean sign) {
        log.debug("Submitting async send task for accountId: {}", accountId);
        return CompletableFuture.supplyAsync(() -> {
            try {
                return send(dto, accountId, encrypt, sign);
            } catch (CoreException e) {
                // wrap into runtime for CF
                throw new CompletionException(e);
            }
        }, sendExecutor);
    }

    @Override
    public void receiveNew(Long accountId) throws CoreException {
        log.info("Starting receiveNew for accountId: {}", accountId);
        try {
            AccountConfig cfg = accountService.getAccountConfig(accountId);
            if (cfg == null) throw new NotFoundException("Account config not found: " + accountId);
            mailAdapter.connect(cfg);

            // fetch folders and process INBOX only (MVP)
            List<ru.study.mailadapter.model.MailFolder> folders = mailAdapter.listFolders();
            Optional<ru.study.mailadapter.model.MailFolder> inbox = folders.stream()
                    .filter(f -> "INBOX".equalsIgnoreCase(f.getName()))
                    .findFirst();

            if (inbox.isPresent()) {
                String folderName = inbox.get().getName();
                log.debug("Processing INBOX folder: {}", folderName);
                EntityManager em = EntityManagerFactoryProvider.createEntityManager();
                try {
                    FolderRepository folderRepository = new FolderRepositoryImpl(em);
                    MessageRepository messageRepository = new MessageRepositoryImpl(em);
                    AttachmentRepository attachmentRepository = new AttachmentRepositoryImpl(em);

                    var folderEntityOpt = folderRepository.findByAccountAndServerName(accountId, folderName);
                    long sinceUid = 0;
                    if (folderEntityOpt.isPresent()) {
                        Long last = folderEntityOpt.get().getLastSyncUid();
                        if (last != null) sinceUid = last;
                    }
                    
                    log.debug("Fetching headers since UID: {}", sinceUid);
                    List<ru.study.mailadapter.model.MailHeader> headers = mailAdapter.fetchHeaders(folderName, sinceUid, 50);
                    log.info("Found {} new messages in folder '{}'", headers.size(), folderName);

                    int processedCount = 0;
                    for (var h : headers) {
                        if (messageRepository.findByAccountAndServerUid(accountId, String.valueOf(h.getUid())).isPresent()) {
                            log.debug("Message with UID {} already exists, skipping", h.getUid());
                            continue;
                        }
                        
                        var raw = mailAdapter.fetchMessage(folderName, h.getUid());
                        EntityTransaction tx = em.getTransaction();
                        try {
                            tx.begin();
                            MessageEntity me = new MessageEntity();
                            me.setAccountId(accountId);
                            folderRepository.findByAccountAndServerName(accountId, folderName).ifPresent(me::setFolder);
                            me.setServerUid(String.valueOf(raw.getUid()));
                            me.setSubject(firstOrEmpty(raw.getHeaders().get("Subject")));
                            me.setSender(firstOrEmpty(raw.getHeaders().get("From")));
                            me.setRecipients("");
                            me.setSentDate(java.time.OffsetDateTime.now());
                            me.setIsSeen(false);
                            MessageEntity saved = messageRepository.save(me);

                            if (raw.getAttachments() != null && !raw.getAttachments().isEmpty()) {
                                log.debug("Processing {} attachments for message UID {}", raw.getAttachments().size(), raw.getUid());
                                for (var ad : raw.getAttachments()) {
                                    AttachmentEntity ae = new AttachmentEntity();
                                    ae.setMessage(saved);
                                    ae.setFilename(ad.getFileName());
                                    ae.setContentType(ad.getContentType());
                                    ae.setFilePath(null);
                                    ae.setSize(ad.getSize() < 0 ? null : ad.getSize());
                                    attachmentRepository.save(ae);
                                }
                            }

                            folderEntityOpt.ifPresent(fe -> {
                                fe.setLastSyncUid(raw.getUid());
                                folderRepository.save(fe);
                            });

                            tx.commit();
                            processedCount++;

                            MessageSummaryDTO summary = MessageMapper.toSummaryDto(MessageMapper.toDomain(saved));
                            eventBus.publish(new NewMessageEvent(summary));
                            
                            log.debug("Successfully processed message UID: {}", raw.getUid());
                        } catch (Exception e) {
                            if (tx.isActive()) tx.rollback();
                            log.error("Failed to persist incoming message uid=" + h.getUid(), e);
                            notificationService.notifyError("Failed to persist incoming message uid=" + h.getUid(), e);
                        }
                    }
                    
                    log.info("Receive completed. Processed {} new messages for accountId: {}", processedCount, accountId);
                } finally {
                    if (em.isOpen()) em.close();
                }
            } else {
                log.warn("INBOX folder not found for accountId: {}", accountId);
            }
        } catch (Exception ex) {
            log.error("Receive failed for accountId: {}", accountId, ex);
            notificationService.notifyError("Receive failed", ex);
            throw new CoreException("Receive failed: " + ex.getMessage(), ex);
        } finally {
            try { mailAdapter.disconnect(); } catch (Exception ignored) {}
        }
    }

    @Override
    public MessageDetailDTO getMessage(Long accountId, Long messageId) throws CoreException {
        log.debug("Getting message detail for accountId: {}, messageId: {}", accountId, messageId);
        // open EM for read-only operations and for key lookup
        EntityManager em = EntityManagerFactoryProvider.createEntityManager();
        try {
            MessageRepository messageRepository = new MessageRepositoryImpl(em);
            KeyRepository keyRepository = new KeyRepositoryImpl(em);
            AccountRepository accountRepository = new AccountRepositoryImpl(em);
            MessageWrappedKeyRepository wrappedRepo = new MessageWrappedKeyRepositoryImpl(em);

            var meOpt = messageRepository.findById(messageId);
            if (meOpt.isEmpty() || !meOpt.get().getAccountId().equals(accountId)) {
                log.warn("Message not found or access denied: accountId={}, messageId={}", accountId, messageId);
                throw new NotFoundException("Message not found");
            }
            MessageEntity me = meOpt.get();
            var domain = MessageMapper.toDomain(me);

            String bodyHtml = null;
            String bodyText = null;
            Boolean sigValid = null;

            if (Boolean.TRUE.equals(me.getIsEncrypted())) {
                try {
                    log.debug("Message is encrypted, attempting decryption");
                    // find wrapped key for this message & recipient (account email)
                    var accOpt = accountRepository.findById(accountId);
                    String myEmail = accOpt.map(a -> a.getEmail()).orElse(null);

                    Optional<MessageWrappedKeyEntity> wrappedOpt = Optional.empty();
                    if (myEmail != null) {
                        wrappedOpt = wrappedRepo.findByMessageAndRecipient(messageId, myEmail);
                        log.debug("Looking for wrapped key for recipient: {}", myEmail);
                    }
                    // fallback: try any wrapped key for the message
                    if (wrappedOpt.isEmpty()) {
                        var all = wrappedRepo.findByMessageId(messageId);
                        if (!all.isEmpty()) {
                            wrappedOpt = Optional.of(all.get(0));
                            log.debug("Using fallback wrapped key for message");
                        }
                    }

                    if (wrappedOpt.isPresent()) {
                        MessageWrappedKeyEntity wk = wrappedOpt.get();
                        byte[] wrappedBlob = wk.getWrappedBlob();

                        // find primary private key for this account
                        var primaryOpt = keyRepository.findPrimaryByAccountId(accountId);
                        if (primaryOpt.isPresent()) {
                            var primary = primaryOpt.get();
                            Long keyId = primary.getId();
                            Optional<char[]> maybeMaster = masterPasswordService.getCurrentMasterPassword();
                            if (maybeMaster.isPresent()) {
                                char[] mp = maybeMaster.get();
                                byte[] pkcs8 = null;
                                byte[] dek = null;
                                try {
                                    pkcs8 = keyManagementService.decryptPrivateKey(keyId, mp); // MUST wipe after use
                                    PrivateKey priv = PemUtils.privateKeyFromPkcs8(pkcs8);
                                    AsymmetricCipher asym = cryptoProviderFactory.getAsymmetricCipher("RSA");
                                    dek = asym.decrypt(priv, wrappedBlob);

                                    // decrypt body
                                    SymmetricCipher sym = cryptoProviderFactory.getSymmetricCipher("DES-ECB");
                                    EncryptedBlob blob = EncryptedBlobCodec.decodeFromBytes(me.getEncryptedBodyBlob());
                                    byte[] plain = sym.decrypt(dek, blob);
                                    bodyText = new String(plain, StandardCharsets.UTF_8);

                                    // wipe plain/dek/pkcs8 after use
                                    Arrays.fill(plain, (byte)0);
                                    log.debug("Successfully decrypted message body");
                                } finally {
                                    if (pkcs8 != null) Arrays.fill(pkcs8, (byte)0);
                                    if (dek != null) Arrays.fill(dek, (byte)0);
                                }
                            } else {
                                log.warn("Master password not available — cannot decrypt message.");
                                notificationService.notifyInfo("Master password not available — cannot decrypt message.");
                            }
                        } else {
                            log.warn("No private key for account — cannot decrypt message.");
                            notificationService.notifyInfo("No private key for account — cannot decrypt message.");
                        }
                    } else {
                        log.warn("No wrapped key entry for this message and recipient.");
                        notificationService.notifyInfo("No wrapped key entry for this message and recipient.");
                    }
                } catch (Exception ex) {
                    log.error("Failed to decrypt message body", ex);
                    notificationService.notifyError("Failed to decrypt message body", ex);
                }
            } else {
                log.debug("Message is not encrypted — trying to extract plain body from entity blob");
                
                byte[] blob = me.getEncryptedBodyBlob();
                
                if (blob != null && blob.length > 0) {
                    try {
                        // Проверяем, есть ли HTML теги в бинарных данных
                        String rawString = new String(blob, StandardCharsets.UTF_8);
                        String lower = rawString.trim().toLowerCase();
                        
                        // простая детекция HTML
                        if (lower.startsWith("<!doctype") || lower.contains("<html") || lower.contains("<body")) {
                            bodyHtml = rawString;
                            log.debug("Detected HTML content from blob, length={}", rawString.length());
                        } else {
                            bodyText = rawString;
                            log.debug("Detected plain text from blob, length={}", rawString.length());
                        }
                    } catch (Exception ex) {
                        log.warn("Failed to decode message body blob as UTF-8", ex);
                        // Пробуем другие кодировки
                        try {
                            String rawString = new String(blob, StandardCharsets.ISO_8859_1);
                            String lower = rawString.trim().toLowerCase();
                            if (lower.startsWith("<!doctype") || lower.contains("<html") || lower.contains("<body")) {
                                bodyHtml = rawString;
                            } else {
                                bodyText = rawString;
                            }
                            log.debug("Loaded body using ISO-8859-1 encoding");
                        } catch (Exception ex2) {
                            log.warn("Failed to decode body with any encoding");
                        }
                    }
                } else {
                    log.debug("No body blob present in entity for message {}", messageId);
                }
            }

            log.debug("Returning message detail for messageId: {}", messageId);
            return MessageMapper.toDetailDto(domain, bodyHtml, bodyText, sigValid);
        } finally {
            if (em != null && em.isOpen()) em.close();
        }
    }

    @Override
    public void deleteMessage(Long accountId, Long messageId) throws CoreException {
        log.info("Deleting message: accountId={}, messageId={}", accountId, messageId);
        EntityManager em = EntityManagerFactoryProvider.createEntityManager();
        try {
            MessageRepository messageRepository = new MessageRepositoryImpl(em);
            var meOpt = messageRepository.findById(messageId);
            if (meOpt.isEmpty()) {
                log.warn("Message not found for deletion: messageId={}", messageId);
                throw new NotFoundException("Message not found");
            }
            MessageEntity me = meOpt.get();
            if (!Objects.equals(me.getAccountId(), accountId)) {
                log.warn("Access denied for message deletion: accountId={}, messageId={}", accountId, messageId);
                throw new CoreException("Not allowed");
            }

            EntityTransaction tx = em.getTransaction();
            try {
                tx.begin();
                me.setIsDeleted(Boolean.TRUE);
                messageRepository.save(me);
                tx.commit();
                log.info("Message marked as deleted: messageId={}", messageId);
                notificationService.notifyInfo("Message marked deleted");
            } catch (Exception ex) {
                if (tx.isActive()) tx.rollback();
                log.error("Delete failed for message: messageId={}", messageId, ex);
                throw new CoreException("Delete failed", ex);
            }
        } finally {
            if (em != null && em.isOpen()) em.close();
        }
    }

    @Override
    public List<MessageSummaryDTO> listMessages(Long accountId, String folder, int page, int size) throws CoreException {
        log.debug("Listing messages: accountId={}, folder={}, page={}, size={}", accountId, folder, page, size);
        EntityManager em = EntityManagerFactoryProvider.createEntityManager();
        try {
            MessageRepository messageRepository = new MessageRepositoryImpl(em);
            FolderRepository folderRepository = new FolderRepositoryImpl(em);
            int offset = Math.max(0, page) * Math.max(1, size);
            List<MessageEntity> list = (folder == null)
                    ? messageRepository.findByAccount(accountId, size, offset)
                    : folderRepository.findByAccountAndServerName(accountId, folder)
                        .map(f -> messageRepository.findByFolder(f.getId(), size, offset))
                        .orElse(List.of());
            
            log.debug("Found {} messages for accountId: {}", list.size(), accountId);
            return list.stream().map(e -> MessageMapper.toSummaryDto(MessageMapper.toDomain(e))).collect(Collectors.toList());
        } finally {
            if (em != null && em.isOpen()) em.close();
        }
    }

    // ---------------- helpers ----------------

    private static byte[] toBytes(InputStream in) throws java.io.IOException {
        try (ByteArrayOutputStream bout = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) bout.write(buf, 0, r);
            return bout.toByteArray();
        } finally {
            try { in.close(); } catch (Exception ignored) {}
        }
    }

    private static String firstOrEmpty(List<String> list) {
        if (list == null || list.isEmpty()) return "";
        return list.get(0);
    }

    @Override
    public void markMessageSeen(Long accountId, Long messageId) throws CoreException {
        log.info("Mark message seen: accountId={}, messageId={}", accountId, messageId);

        EntityManager em = EntityManagerFactoryProvider.createEntityManager();
        try {
            MessageRepository messageRepository = new MessageRepositoryImpl(em);
            FolderRepository folderRepository = new FolderRepositoryImpl(em);

            var meOpt = messageRepository.findById(messageId);
            if (meOpt.isEmpty()) {
                log.warn("Message not found: {}", messageId);
                throw new NotFoundException("Message not found");
            }
            MessageEntity me = meOpt.get();
            if (!Objects.equals(me.getAccountId(), accountId)) {
                log.warn("Access denied for markAsSeen: accountId={}, messageId={}", accountId, messageId);
                throw new CoreException("Not allowed");
            }

            // Try to mark on server if we have serverUid and folder info
            String serverUid = me.getServerUid();
            String folderName = null;
            if (me.getFolder() != null) {
                try {
                    // FolderEntity likely has getServerName()
                    java.lang.reflect.Method m = me.getFolder().getClass().getMethod("getServerName");
                    Object val = m.invoke(me.getFolder());
                    if (val != null) folderName = val.toString();
                } catch (NoSuchMethodException | IllegalAccessException | java.lang.reflect.InvocationTargetException ignored) {
                    // fallback: try toString or null
                }
            }

            if (serverUid != null && !serverUid.isBlank() && folderName != null) {
                AccountConfig cfg = accountService.getAccountConfig(accountId);
                if (cfg != null) {
                    try {
                        mailAdapter.connect(cfg);
                        try {
                            long uid = Long.parseLong(serverUid);
                            mailAdapter.markMessageSeen(folderName, uid, true);
                        } catch (NumberFormatException nfe) {
                            log.warn("Server UID is not numeric: {}", serverUid);
                        } finally {
                            try { mailAdapter.disconnect(); } catch (Exception ignored) {}
                        }
                    } catch (Exception e) {
                        // don't fail the whole flow if server update fails — just log and continue to update DB
                        log.warn("Failed to mark message seen on server: {}", e.getMessage());
                        notificationService.notifyInfo("Failed to mark message seen on server: " + e.getMessage());
                        try { mailAdapter.disconnect(); } catch (Exception ignored) {}
                    }
                }
            }

            // Update DB record
            EntityTransaction tx = em.getTransaction();
            try {
                tx.begin();
                me.setIsSeen(Boolean.TRUE);
                messageRepository.save(me);
                tx.commit();
                log.debug("Marked message as seen in DB: {}", messageId);
            } catch (Exception ex) {
                if (tx.isActive()) tx.rollback();
                log.error("Failed to mark message seen in DB: {}", messageId, ex);
                throw new CoreException("Failed to mark message seen", ex);
            }
        } finally {
            if (em != null && em.isOpen()) em.close();
        }
    }

}