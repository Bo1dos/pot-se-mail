package ru.study.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

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
import ru.study.core.model.EmailAddress;
import ru.study.service.api.MailService;
import ru.study.service.api.AccountService;
import ru.study.service.api.AttachmentService;
import ru.study.service.api.KeyManagementService;
import ru.study.service.api.MasterPasswordService;
import ru.study.service.api.NotificationService;
import ru.study.service.client.KeyServerClient;
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

    AccountConfig cfg = accountService.getAccountConfig(accountId);
    if (cfg == null) {
        log.error("Account config not found for accountId: {}", accountId);
        throw new NotFoundException("Account config not found: " + accountId);
    }

    // temp data structures for attachments (file paths / streams)
    List<InputStream> streamsToClose = new ArrayList<>();
    Map<ru.study.service.dto.OutgoingAttachmentDTO, java.nio.file.Path> persistPath = new LinkedHashMap<>();
    List<OutgoingAttachment> outgoingAttachments = new ArrayList<>();

    // Map for wrapped keys per recipient
    Map<String, byte[]> wrappedPerRecipient = null;

    // --- collect attachment sources (plain files or temp copies) ---
    try {
        if (dto.getAttachments() != null) {
            for (ru.study.service.dto.OutgoingAttachmentDTO a : dto.getAttachments()) {
                if (a.getFilePath() != null) {
                    java.nio.file.Path p = java.nio.file.Paths.get(a.getFilePath());
                    InputStream sendIn = java.nio.file.Files.newInputStream(p, java.nio.file.StandardOpenOption.READ);
                    outgoingAttachments.add(new OutgoingAttachment(a.getFileName(), a.getContentType(), sendIn));
                    streamsToClose.add(sendIn);
                    persistPath.put(a, p);
                } else {
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
                    persistPath.put(a, tmp);
                }
            }
        }

        // Initial outgoing with plaintext
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

        // --- CRYPTO: prepare variables visible in wider scope ---
        byte[] symKey = null;
        SymmetricCipher sym = null;
        byte[] encryptedBodyBlob = null;
        byte[] bodyIv = null;
        byte[] signatureBlob = null;

        // Map to store encrypted attachments for persistence
        Map<ru.study.service.dto.OutgoingAttachmentDTO, byte[]> encryptedAttachmentsMap = new HashMap<>();

        try {
            // --- Collect all recipients ---
            Set<String> allRecipients = new LinkedHashSet<>();
            if (dto.getTo() != null) allRecipients.addAll(dto.getTo());
            if (dto.getCc() != null) allRecipients.addAll(dto.getCc());
            if (dto.getBcc() != null) allRecipients.addAll(dto.getBcc());

            // 1) generate sym key and encrypt body (if requested)
            if (encrypt) {
                try {
                    log.debug("Starting encryption for message to recipients: {}", allRecipients);
                    sym = cryptoProviderFactory.getSymmetricCipher("DES-ECB");
                    symKey = sym.generateKey();
                    
                    // Encrypt body
                    byte[] bodyPlain = dto.getBody() == null ? new byte[0] : dto.getBody().getBytes(StandardCharsets.UTF_8);
                    EncryptedBlob bodyEnc = sym.encrypt(symKey, bodyPlain);
                    encryptedBodyBlob = EncryptedBlobCodec.encodeToBytes(bodyEnc);
                    bodyIv = bodyEnc.iv();
                    
                    // Encrypt symmetric key for each recipient with their public key
                    wrappedPerRecipient = new HashMap<>();
                    AsymmetricCipher asym = cryptoProviderFactory.getAsymmetricCipher("RSA");
                    boolean allHaveKey = true;
                    
                    for (String recipient : allRecipients) {
                        log.debug("Looking up public key for recipient: {}", recipient);
                        Optional<KeyDTO> recipientKey = keyServerClient.findKeyByEmail(recipient);
                        if (recipientKey.isEmpty()) {
                            // try to find locally via KeyManagementService
                            recipientKey = keyManagementService.findPublicKeyByEmail(recipient);
                        }
                        
                        if (recipientKey.isPresent() && recipientKey.get().publicKeyPem() != null) {
                            String pem = recipientKey.get().publicKeyPem();
                            PublicKey pub = PemUtils.publicKeyFromPem(pem);
                            byte[] wrapped = asym.encrypt(pub, symKey);
                            wrappedPerRecipient.put(recipient, wrapped);
                            log.debug("Encrypted symmetric key for recipient: {}", recipient);
                        } else {
                            log.warn("No public key found for recipient: {}", recipient);
                            allHaveKey = false;
                        }
                    }
                    
                    if (wrappedPerRecipient.isEmpty() || !allHaveKey) {
                        log.warn("Not all recipients have public keys - skipping encryption (sending plaintext)");
                        notificationService.notifyInfo("No public key for recipient(s). Sending unencrypted.");
                        
                        // Clean up
                        if (symKey != null) Arrays.fill(symKey, (byte)0);
                        symKey = null;
                        encryptedBodyBlob = null;
                        bodyIv = null;
                        wrappedPerRecipient = null;
                        encrypt = false;
                    } else {
                        log.debug("Encryption completed successfully for all recipients. Wrapped keys: {}", wrappedPerRecipient.size());
                        
                        // Encrypt attachments for sending
                        if (!persistPath.isEmpty()) {
                            log.debug("Encrypting attachments for outbound mail using message symmetric key");
                            // Close previously opened send streams
                            for (InputStream is : streamsToClose) {
                                try { is.close(); } catch (Exception ignored) {}
                            }
                            streamsToClose.clear();
                            outgoingAttachments.clear();
                            
                            for (var entry : persistPath.entrySet()) {
                                var aDto = entry.getKey();
                                var path = entry.getValue();
                                byte[] fileBytes = java.nio.file.Files.readAllBytes(path);
                                EncryptedBlob attEnc = sym.encrypt(symKey, fileBytes);
                                byte[] payload = EncryptedBlobCodec.encodeToBytes(attEnc);
                                
                                // Store for persistence
                                encryptedAttachmentsMap.put(aDto, payload);
                                
                                InputStream encIn = new ByteArrayInputStream(payload);
                                outgoingAttachments.add(new OutgoingAttachment(aDto.getFileName(), aDto.getContentType(), encIn));
                                streamsToClose.add(encIn);
                                
                                // Clean up
                                Arrays.fill(fileBytes, (byte)0);
                            }
                        }
                        
                        // Replace outgoing body with Base64 of encrypted blob
                        String bodyBase64 = Base64.getEncoder().encodeToString(encryptedBodyBlob);
                        
                        // ============ ДОБАВЛЯЕМ WRAPPED KEYS КАК ВЛОЖЕНИЕ ============
                        // Создаем JSON со всеми wrapped keys
                        Map<String, String> keysJson = new HashMap<>();
                        for (Map.Entry<String, byte[]> entry : wrappedPerRecipient.entrySet()) {
                            keysJson.put(entry.getKey(), Base64.getEncoder().encodeToString(entry.getValue()));
                        }
                        
                        try {
                            // Используем Jackson для создания JSON
                            ObjectMapper mapper = new ObjectMapper();
                            String keysJsonStr = mapper.writeValueAsString(keysJson);
                            
                            // Добавляем как вложение
                            InputStream keysStream = new ByteArrayInputStream(keysJsonStr.getBytes(StandardCharsets.UTF_8));
                            outgoingAttachments.add(new OutgoingAttachment("encryption_keys.json", "application/json", keysStream));
                            streamsToClose.add(keysStream);
                            
                            log.debug("Added encryption_keys.json attachment with {} keys", wrappedPerRecipient.size());
                        } catch (Exception e) {
                            log.error("Failed to create keys JSON", e);
                        }
                        // ============ КОНЕЦ ДОБАВЛЕНИЯ WRAPPED KEYS ============
                        
                        outgoing = new RawOutgoingMail(
                                dto.getFrom(),
                                dto.getTo(),
                                dto.getCc(),
                                dto.getBcc(),
                                dto.getSubject(),
                                bodyBase64,
                                false, // not HTML - because body is base64 payload
                                outgoingAttachments
                        );
                        
                        log.debug("Outgoing body replaced with Base64 encrypted blob, length: {}", bodyBase64.length());
                    }
                    
                    // Clean up
                    Arrays.fill(bodyPlain, (byte)0);
                } catch (Exception ex) {
                    log.error("Encryption failed, sending plaintext", ex);
                    notificationService.notifyError("Encryption failed, sending plaintext", ex);
                    if (symKey != null) Arrays.fill(symKey, (byte)0);
                    symKey = null;
                    encryptedBodyBlob = null;
                    bodyIv = null;
                    wrappedPerRecipient = null;
                    encrypt = false;
                }
            }

            // 2) Signing
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
                                    
                                    // Sign the body (plaintext, not encrypted)
                                    byte[] dataToSign = dto.getBody() == null ? new byte[0] : dto.getBody().getBytes(StandardCharsets.UTF_8);
                                    signatureBlob = signer.sign(priv, dataToSign);
                                    log.debug("Message signed successfully with key ID: {}, signature length: {}", keyId, signatureBlob.length);
                                    
                                    // Add signature as attachment
                                    if (signatureBlob != null) {
                                        InputStream sigIn = new ByteArrayInputStream(signatureBlob);
                                        outgoingAttachments.add(new OutgoingAttachment("signature.sig", "application/octet-stream", sigIn));
                                        streamsToClose.add(sigIn);
                                        
                                        // Recreate outgoing with signature attachment
                                        List<OutgoingAttachment> newAttachments = new ArrayList<>(outgoingAttachments);
                                        outgoing = new RawOutgoingMail(
                                                outgoing.getFrom(),
                                                outgoing.getTo(),
                                                outgoing.getCc(),
                                                outgoing.getBcc(),
                                                outgoing.getSubject(),
                                                outgoing.getBody(),
                                                outgoing.isHtml(),
                                                newAttachments
                                        );
                                        log.debug("Signature added as attachment: signature.sig");
                                    }
                                    
                                    Arrays.fill(dataToSign, (byte)0);
                                } finally {
                                    Arrays.fill(pkcs8, (byte)0);
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

            // 4) connect adapter and send
            try {
                log.debug("Connecting to mail adapter for sending. Body length: {}, HTML: {}", 
                        outgoing.getBody().length(), outgoing.isHtml());
                mailAdapter.connect(cfg);
                mailAdapter.send(outgoing);
                log.info("Mail sent via adapter for accountId={}, subject='{}'", accountId, dto.getSubject());
            } catch (Exception ex) {
                log.error("Failed to send message via SMTP", ex);
                notificationService.notifyError("Failed to send message via SMTP", ex);
                throw new CoreException("Failed to send message via SMTP: " + ex.getMessage(), ex);
            } finally {
                try { mailAdapter.disconnect(); } catch (Exception ignored) {}
            }

            // close send streams
            for (InputStream is : streamsToClose) {
                try { is.close(); } catch (Exception ignored) {}
            }
            streamsToClose.clear();

            // 5) Persist message + wrapped keys + attachments (atomic tx)
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

                    // Wrapped keys - per recipient (сохраняем в БД для локального использования)
                    if (wrappedPerRecipient != null && !wrappedPerRecipient.isEmpty()) {
                        for (var entry : wrappedPerRecipient.entrySet()) {
                            MessageWrappedKeyEntity wk = new MessageWrappedKeyEntity();
                            wk.setMessage(saved);
                            wk.setRecipient(entry.getKey());
                            wk.setWrappedBlob(entry.getValue());
                            wrappedRepo.save(wk);
                        }
                        log.debug("Persisted wrapped keys for {} recipients", wrappedPerRecipient.size());
                    }

                    // Persist attachments encrypted with symKey (if symKey==null => store plaintext)
                    final java.nio.file.Path attachmentsDir = java.nio.file.Paths.get("./data/attachments");
                    try { java.nio.file.Files.createDirectories(attachmentsDir); } catch (Exception ignored) {}

                    final long ATTACH_DB_THRESHOLD = 5_242_880L; // 5MB threshold

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

                            if (symKey != null && sym != null) {
                                // Use encrypted attachment from map
                                byte[] encBytes = encryptedAttachmentsMap.get(aDto);
                                if (encBytes == null) {
                                    // Fallback: read and encrypt
                                    byte[] raw = java.nio.file.Files.readAllBytes(path);
                                    EncryptedBlob attEnc = sym.encrypt(symKey, raw);
                                    encBytes = EncryptedBlobCodec.encodeToBytes(attEnc);
                                    ae.setIv(attEnc.iv());
                                    Arrays.fill(raw, (byte)0);
                                } else {
                                    // Already have encrypted bytes, need to extract IV from them
                                    EncryptedBlob attEnc = EncryptedBlobCodec.decodeFromBytes(encBytes);
                                    ae.setIv(attEnc.iv());
                                }

                                if (encBytes.length <= ATTACH_DB_THRESHOLD) {
                                    ae.setEncryptedBlob(encBytes);
                                    ae.setFilePath(null);
                                    attachmentRepository.save(ae);
                                } else {
                                    // store encrypted blob to file on FS
                                    String sanitized = ae.getFilename().replaceAll("[^a-zA-Z0-9._-]", "_");
                                    String safeName = UUID.randomUUID() + "_" + sanitized + ".enc";
                                    java.nio.file.Path finalPath = attachmentsDir.resolve(safeName);
                                    java.nio.file.Files.write(finalPath, encBytes, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
                                    ae.setFilePath(finalPath.toString());
                                    ae.setEncryptedBlob(null);
                                    attachmentRepository.save(ae);
                                }
                            } else {
                                // store plaintext (fallback)
                                byte[] raw = java.nio.file.Files.readAllBytes(path);
                                if (raw.length <= ATTACH_DB_THRESHOLD) {
                                    ae.setEncryptedBlob(raw);
                                    ae.setIv(null);
                                    ae.setFilePath(null);
                                    attachmentRepository.save(ae);
                                } else {
                                    String sanitized = ae.getFilename().replaceAll("[^a-zA-Z0-9._-]", "_");
                                    String safeName = UUID.randomUUID() + "_" + sanitized;
                                    java.nio.file.Path finalPath = attachmentsDir.resolve(safeName);
                                    try {
                                        java.nio.file.Files.move(path, finalPath, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                                    } catch (java.nio.file.AtomicMoveNotSupportedException amnse) {
                                        java.nio.file.Files.move(path, finalPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                                    }
                                    ae.setFilePath(finalPath.toString());
                                    ae.setEncryptedBlob(null);
                                    ae.setIv(null);
                                    attachmentRepository.save(ae);
                                }
                                Arrays.fill(raw, (byte)0);
                            }

                            // cleanup temp if needed
                            if (aDto.getFilePath() == null) {
                                try { java.nio.file.Files.deleteIfExists(path); } catch (Exception ignored) {}
                            }
                        } catch (Exception ex) {
                            log.warn("Failed to save outgoing attachment '{}' : {}", aDto.getFileName(), ex.getMessage());
                        }
                    }

                    tx.commit();
                    MessageSummaryDTO summary = MessageMapper.toSummaryDto(MessageMapper.toDomain(saved));
                    eventBus.publish(new NewMessageEvent(summary));
                    log.info("Send completed successfully for message ID: {}", saved.getId());
                    return SendResultDTO.builder().success(true).messageId(String.valueOf(saved.getId())).error(null).build();
                } catch (Exception e) {
                    if (tx.isActive()) tx.rollback();
                    log.error("Failed to persist sent message", e);
                    notificationService.notifyError("Failed to persist sent message", e);
                    throw new CoreException("Failed to persist sent message: " + e.getMessage(), e);
                } finally {
                    if (em.isOpen()) em.close();
                }
            } catch (Exception e) {
                log.error("Persist after send failed", e);
                notificationService.notifyError("Persist after send failed", e);
                throw new CoreException("Persist after send failed: " + e.getMessage(), e);
            }
        } catch (CoreException ce) {
            throw ce;
        } catch (Exception ex) {
            log.error("Unexpected exception during send process", ex);
            notificationService.notifyError("Send failed", ex);
            throw new CoreException("Send failed: " + ex.getMessage(), ex);
        } finally {
            // wipe symKey if still present
            if (symKey != null) Arrays.fill(symKey, (byte)0);
            // cleanup streams & temp files
            for (InputStream is : streamsToClose) try { is.close(); } catch (Exception ignored) {}
            for (var entry : persistPath.entrySet()) {
                var a = entry.getKey();
                var path = entry.getValue();
                if (a.getFilePath() == null) {
                    try { java.nio.file.Files.deleteIfExists(path); } catch (Exception ignored) {}
                }
            }
        }
    } catch (Exception ex) {
        log.error("Unexpected error in send method", ex);
        throw new CoreException("Send failed: " + ex.getMessage(), ex);
    }
}

    @Override
    public MessageDetailDTO getMessage(Long accountId, Long messageId) throws CoreException {
        log.debug("Getting message detail for accountId: {}, messageId: {}", accountId, messageId);
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
            

            String bodyHtml = null;
            String bodyText = null;
            Boolean sigValid = null;

            // временный контейнер для подписи, если она придёт как вложение
            byte[] sigFromAttachment = null;

            if (Boolean.TRUE.equals(me.getIsEncrypted())) {
                log.debug("Message is encrypted, attempting decryption");
                try {
                    var accOpt = accountRepository.findById(accountId);
                    String myEmail = accOpt.map(a -> a.getEmail()).orElse(null);

                    // Получаем все wrapped-ключи для сообщения (может быть несколько)
                    List<MessageWrappedKeyEntity> wrappedAll = wrappedRepo.findByMessageId(messageId);
                    if (wrappedAll == null || wrappedAll.isEmpty()) {
                        log.warn("No wrapped keys stored for messageId={}, cannot decrypt", messageId);
                        notificationService.notifyInfo("No wrapped keys for message — cannot decrypt.");
                    } else {
                        log.debug("Found {} wrapped keys for messageId={}", wrappedAll.size(), messageId);

                        var primaryOpt = keyRepository.findPrimaryByAccountId(accountId);
                        if (primaryOpt.isEmpty()) {
                            log.warn("Primary private key not found for account {} — cannot decrypt message.", accountId);
                            notificationService.notifyInfo("Primary private key not found for account — cannot decrypt message.");
                        } else {
                            var primary = primaryOpt.get();
                            Long keyId = primary.getId();
                            Optional<char[]> maybeMaster = masterPasswordService.getCurrentMasterPassword();
                            if (maybeMaster.isEmpty()) {
                                log.warn("Master password not available — cannot decrypt message for account {}", accountId);
                                notificationService.notifyInfo("Master password not available — cannot decrypt message.");
                            } else {
                                char[] mp = maybeMaster.get();
                                byte[] pkcs8 = null;
                                try {
                                    pkcs8 = keyManagementService.decryptPrivateKey(keyId, mp); // must wipe later
                                    PrivateKey priv = PemUtils.privateKeyFromPkcs8(pkcs8);
                                    AsymmetricCipher asym = cryptoProviderFactory.getAsymmetricCipher("RSA");
                                    SymmetricCipher sym = cryptoProviderFactory.getSymmetricCipher("DES-ECB");

                                    // Decode stored encrypted body once
                                    EncryptedBlob bodyBlob = null;
                                    try {
                                        bodyBlob = EncryptedBlobCodec.decodeFromBytes(me.getEncryptedBodyBlob());
                                    } catch (Exception e) {
                                        log.warn("Failed to decode EncryptedBlob from DB for messageId={}: {}", messageId, e.getMessage());
                                    }

                                    // Если нет blob — логим и прекращаем попытки дешифровки тела (но попробуем подпись из вложений).
                                    if (bodyBlob == null) {
                                        log.warn("Encrypted body blob is null/invalid for messageId={}", messageId);
                                    } else {
                                        // пробуем каждый wrapped blob — как минимум один может быть для нас
                                        boolean decryptedOk = false;
                                        for (MessageWrappedKeyEntity wk : wrappedAll) {
                                            byte[] wrappedBlob = wk.getWrappedBlob();
                                            if (wrappedBlob == null || wrappedBlob.length == 0) continue;

                                            byte[] dek = null;
                                            try {
                                                // Попытка расшифровать dek
                                                try {
                                                    dek = asym.decrypt(priv, wrappedBlob);
                                                    log.debug("Successfully unwrapped symmetric key using wrappedKey id={} (recipient={})",
                                                            wk.getId(), wk.getRecipient());
                                                } catch (Exception exWrap) {
                                                    log.debug("Failed to unwrap symmetric key for wrappedKey id={} recipient={} : {}",
                                                            wk.getId(), wk.getRecipient(), exWrap.getMessage());
                                                    continue; // пробуем следующий wrappedKey
                                                }

                                                // Попытка расшифровать тело
                                                byte[] plain = null;
                                                try {
                                                    plain = sym.decrypt(dek, bodyBlob);
                                                } catch (Exception exDec) {
                                                    log.debug("sym.decrypt failed with this dek from wrappedKey id={} : {}", wk.getId(), exDec.getMessage());
                                                    continue; // пробуем следующий wrappedKey
                                                }

                                                // проверим результат — не пустой и не служебный маркер
                                                if (plain != null && plain.length > 0) {
                                                    String candidate = null;
                                                    try {
                                                        candidate = new String(plain, StandardCharsets.UTF_8);
                                                    } catch (Exception e) {
                                                        // ничего, будем считать невалидным и пробуем дальше
                                                        candidate = null;
                                                    }

                                                    // простая эвристика: если plaintext выглядит как "DES/ECB/..." или содержит '||' маркер,
                                                    // значит мы, возможно, дешифровали не тот кусок — пробуем дальше
                                                    boolean suspicious = candidate == null
                                                            || candidate.startsWith("DES/") 
                                                            || candidate.contains("||") && candidate.length() < 200;

                                                    if (!suspicious) {
                                                        bodyText = candidate;
                                                        Arrays.fill(plain, (byte)0);
                                                        log.info("Decrypted message body successfully for messageId={} using wrappedKey id={}", messageId, wk.getId());
                                                        decryptedOk = true;
                                                        // не забываем затереть dek
                                                        Arrays.fill(dek, (byte)0);
                                                        break; // успех — выход из цикла wrapped keys
                                                    } else {
                                                        log.debug("Decrypted candidate looks suspicious for wrappedKey id={} — continuing search", wk.getId());
                                                    }
                                                    Arrays.fill(plain, (byte)0);
                                                }
                                            } finally {
                                                if (dek != null) Arrays.fill(dek, (byte)0);
                                            }
                                        } // for wrapped keys

                                        if (!decryptedOk) {
                                            log.warn("Tried all wrapped keys for messageId={} but none produced valid plaintext", messageId);
                                            notificationService.notifyInfo("Failed to decrypt message with available keys.");
                                        }
                                    } // bodyBlob != null

                                    // decrypt attachments (if any) — при этом: если attachment выглядит как подпись, сохраняем её в память
                                    java.nio.file.Path attachmentsDir = java.nio.file.Paths.get("./data/attachments");
                                    try { java.nio.file.Files.createDirectories(attachmentsDir); } catch (Exception ignored) {}

                                    if (me.getAttachments() != null) {
                                        for (AttachmentEntity ae : me.getAttachments()) {
                                            try {
                                                byte[] encBytes = null;
                                                if (ae.getEncryptedBlob() != null && ae.getEncryptedBlob().length > 0) {
                                                    encBytes = ae.getEncryptedBlob();
                                                } else if (ae.getFilePath() != null) {
                                                    java.nio.file.Path p = java.nio.file.Paths.get(ae.getFilePath());
                                                    if (java.nio.file.Files.exists(p)) encBytes = java.nio.file.Files.readAllBytes(p);
                                                }

                                                if (encBytes != null && encBytes.length > 0) {
                                                    EncryptedBlob attBlob = EncryptedBlobCodec.decodeFromBytes(encBytes);
                                                    // Для расшифровки attachments нам нужен dek — пытаем тот же подход: найти любой wrappedKey,
                                                    // который мы уже смогли успешно распаковать выше. Но проще: повторно пробуем те же wrappedAll,
                                                    // остановимся при первом успешном декоде (логика может быть оптимизирована).
                                                    boolean attDecrypted = false;
                                                    for (MessageWrappedKeyEntity wk : wrappedAll) {
                                                        byte[] dekAtt = null;
                                                        try {
                                                            dekAtt = asym.decrypt(priv, wk.getWrappedBlob());
                                                            byte[] plainAtt = null;
                                                            try {
                                                                plainAtt = sym.decrypt(dekAtt, attBlob);
                                                            } catch (Exception ex) {
                                                                continue; // следующий wrapped key
                                                            }

                                                            // если получилось — сохраним в temp файл
                                                            String filename = ae.getFilename() == null ? "attachment" : ae.getFilename();
                                                            String sanitized = filename.replaceAll("[^a-zA-Z0-9._-]", "_");
                                                            java.nio.file.Path tmp = java.nio.file.Files.createTempFile(attachmentsDir, "dec-", "_" + sanitized);
                                                            java.nio.file.Files.write(tmp, plainAtt, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
                                                            ae.setFilePath(tmp.toString());

                                                            // если это выглядит как подпись — сохраним копию в память
                                                            String low = filename.toLowerCase(Locale.ROOT);
                                                            boolean looksLikeSignature = low.equals("signature.sig") || low.endsWith(".sig") || low.contains("signature");
                                                            if (looksLikeSignature) {
                                                                sigFromAttachment = Arrays.copyOf(plainAtt, plainAtt.length);
                                                                log.debug("Found signature attachment '{}' and loaded into memory (len={})", filename, sigFromAttachment.length);
                                                            }

                                                            Arrays.fill(plainAtt, (byte)0);
                                                            attDecrypted = true;
                                                            break;
                                                        } catch (Exception exInner) {
                                                            // продолжим цикл wrapped keys
                                                        } finally {
                                                            if (dekAtt != null) Arrays.fill(dekAtt, (byte)0);
                                                        }
                                                    } // for wrappedAll

                                                    if (!attDecrypted) {
                                                        log.warn("Could not decrypt attachment '{}' for messageId={}", ae.getFilename(), messageId);
                                                    }
                                                } else {
                                                    log.debug("Attachment {} has no encrypted data available", ae.getFilename());
                                                }
                                            } catch (Exception ex) {
                                                log.warn("Failed to decrypt attachment {}: {}", ae.getFilename(), ex.getMessage());
                                            }
                                        } // for attachments
                                    } // me.getAttachments != null

                                    // verify signature (если есть) — сначала пробуем me.signatureBlob, затем sigFromAttachment
                                    byte[] signatureBlob = me.getSignatureBlob() != null && me.getSignatureBlob().length > 0 ? me.getSignatureBlob() : sigFromAttachment;
                                    if (signatureBlob != null && signatureBlob.length > 0) {
                                        try {
                                            // determine sender email heuristically
                                            String senderRaw = me.getSender();
                                            String senderEmail = null;
                                            if (senderRaw != null) {
                                                int lt = senderRaw.indexOf('<');
                                                int gt = senderRaw.indexOf('>');
                                                if (lt >= 0 && gt > lt) senderEmail = senderRaw.substring(lt+1, gt).trim();
                                                else {
                                                    String[] parts = senderRaw.split("\\s+");
                                                    for (String p : parts) if (p.contains("@")) { senderEmail = p.trim(); break; }
                                                }
                                            }

                                            Optional<KeyDTO> senderKeyOpt = Optional.empty();
                                            if (senderEmail != null) {
                                                senderKeyOpt = keyManagementService.findPublicKeyByEmail(senderEmail);
                                                if (senderKeyOpt.isEmpty()) {
                                                    var kk = keyServerClient.findKeyByEmail(senderEmail);
                                                    if (kk.isPresent()) {
                                                        keyManagementService.importPublicKey(accountId, kk.get().publicKeyPem());
                                                        senderKeyOpt = keyManagementService.findPublicKeyByEmail(senderEmail);
                                                    }
                                                }
                                            }

                                            if (senderKeyOpt.isPresent()) {
                                                PublicKey pub = PemUtils.publicKeyFromPem(senderKeyOpt.get().publicKeyPem());
                                                Signer signer = cryptoProviderFactory.getSigner("MD5withRSA");

                                                byte[] dataToVerify;
                                                if (bodyText != null) dataToVerify = bodyText.getBytes(StandardCharsets.UTF_8);
                                                else if (bodyHtml != null) dataToVerify = bodyHtml.getBytes(StandardCharsets.UTF_8);
                                                else dataToVerify = new byte[0];

                                                sigValid = signer.verify(pub, dataToVerify, signatureBlob);
                                                Arrays.fill(dataToVerify, (byte)0);
                                                log.debug("Signature verification result: {}", sigValid);
                                            } else {
                                                log.warn("Public key for sender not found — cannot verify signature");
                                                notificationService.notifyInfo("Public key for sender not found — cannot verify signature");
                                                sigValid = null;
                                            }
                                        } catch (Exception ex) {
                                            log.warn("Signature verification failed: {}", ex.getMessage());
                                            sigValid = false;
                                        } finally {
                                            if (sigFromAttachment != null) {
                                                Arrays.fill(sigFromAttachment, (byte)0);
                                                sigFromAttachment = null;
                                            }
                                        }
                                    } // signatureBlob != null

                                } finally {
                                    if (pkcs8 != null) Arrays.fill(pkcs8, (byte)0);
                                }
                            } // master present
                        } // primary present
                    } // wrappedAll not empty
                } catch (Exception ex) {
                    log.error("Failed to decrypt message body or attachments", ex);
                    notificationService.notifyError("Failed to decrypt message", ex);
                }
            } else {
                // message not encrypted: try to read plaintext body from blob field
                byte[] blob = me.getEncryptedBodyBlob();
                if (blob != null && blob.length > 0) {
                    try {
                        String rawString = new String(blob, StandardCharsets.UTF_8);
                        String lower = rawString.trim().toLowerCase();
                        if (lower.startsWith("<!doctype") || lower.contains("<html") || lower.contains("<body")) {
                            bodyHtml = rawString;
                        } else {
                            bodyText = rawString;
                        }
                    } catch (Exception ex) {
                        log.warn("Failed to decode message body blob as UTF-8", ex);
                        try {
                            String rawString = new String(blob, StandardCharsets.ISO_8859_1);
                            String lower = rawString.trim().toLowerCase();
                            if (lower.startsWith("<!doctype") || lower.contains("<html") || lower.contains("<body")) bodyHtml = rawString;
                            else bodyText = rawString;
                        } catch (Exception ex2) { /* ignore */ }
                    }
                }

                // как прежде: пробуем найти подпись среди вложений если me.signatureBlob пуст
                if ((me.getSignatureBlob() == null || me.getSignatureBlob().length == 0) && me.getAttachments() != null) {
                    for (AttachmentEntity ae : me.getAttachments()) {
                        try {
                            String filename = ae.getFilename() == null ? "" : ae.getFilename();
                            String low = filename.toLowerCase(Locale.ROOT);
                            boolean looksLikeSignature = low.equals("signature.sig") || low.endsWith(".sig") || low.contains("signature");
                            if (!looksLikeSignature) continue;

                            byte[] data = null;
                            if (ae.getEncryptedBlob() != null && ae.getEncryptedBlob().length > 0) {
                                data = ae.getEncryptedBlob();
                            } else if (ae.getFilePath() != null) {
                                java.nio.file.Path p = java.nio.file.Paths.get(ae.getFilePath());
                                if (java.nio.file.Files.exists(p)) data = java.nio.file.Files.readAllBytes(p);
                            }

                            if (data != null && data.length > 0) {
                                sigFromAttachment = Arrays.copyOf(data, data.length);
                                log.debug("Loaded signature from plaintext attachment '{}', len={}", filename, sigFromAttachment.length);
                                break;
                            }
                        } catch (Exception ex) {
                            log.warn("Failed to read potential signature attachment {}: {}", ae.getFilename(), ex.getMessage());
                        }
                    }

                    if (sigFromAttachment != null) {
                        try {
                            String senderRaw = me.getSender();
                            String senderEmail = null;
                            if (senderRaw != null) {
                                int lt = senderRaw.indexOf('<');
                                int gt = senderRaw.indexOf('>');
                                if (lt >= 0 && gt > lt) senderEmail = senderRaw.substring(lt+1, gt).trim();
                                else {
                                    String[] parts = senderRaw.split("\\s+");
                                    for (String p : parts) if (p.contains("@")) { senderEmail = p.trim(); break; }
                                }
                            }

                            Optional<KeyDTO> senderKeyOpt = Optional.empty();
                            if (senderEmail != null) {
                                senderKeyOpt = keyManagementService.findPublicKeyByEmail(senderEmail);
                                if (senderKeyOpt.isEmpty()) {
                                    var kk = keyServerClient.findKeyByEmail(senderEmail);
                                    if (kk.isPresent()) {
                                        keyManagementService.importPublicKey(accountId, kk.get().publicKeyPem());
                                        senderKeyOpt = keyManagementService.findPublicKeyByEmail(senderEmail);
                                    }
                                }
                            }

                            if (senderKeyOpt.isPresent()) {
                                PublicKey pub = PemUtils.publicKeyFromPem(senderKeyOpt.get().publicKeyPem());
                                Signer signer = cryptoProviderFactory.getSigner("MD5withRSA");

                                byte[] dataToVerify;
                                if (bodyText != null) dataToVerify = bodyText.getBytes(StandardCharsets.UTF_8);
                                else if (bodyHtml != null) dataToVerify = bodyHtml.getBytes(StandardCharsets.UTF_8);
                                else dataToVerify = new byte[0];

                                sigValid = signer.verify(pub, dataToVerify, sigFromAttachment);
                                Arrays.fill(dataToVerify, (byte)0);
                                log.debug("Signature verification result (from attachment): {}", sigValid);
                            } else {
                                log.warn("Public key for sender not found — cannot verify signature (attachment)");
                                notificationService.notifyInfo("Public key for sender not found — cannot verify signature");
                                sigValid = null;
                            }
                        } catch (Exception ex) {
                            log.warn("Signature verification failed (from attachment): {}", ex.getMessage());
                            sigValid = false;
                        } finally {
                            Arrays.fill(sigFromAttachment, (byte)0);
                            sigFromAttachment = null;
                        }
                    }
                }
            }

            // map to domain so attachments/filePaths reflect temp decrypted files created above
            var domain = MessageMapper.toDomain(me);
            log.debug("Returning message detail for messageId: {}", messageId);
            return MessageMapper.toDetailDto(domain, bodyHtml, bodyText, sigValid);
        } finally {
            if (em != null && em.isOpen()) em.close();
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

    @Override
    public void markMessageSeen(Long accountId, Long messageId) throws CoreException {
        log.info("Mark message seen: accountId={}, messageId={}", accountId, messageId);

        EntityManager em = EntityManagerFactoryProvider.createEntityManager();
        try {
            MessageRepository messageRepository = new MessageRepositoryImpl(em);

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

    // ---------------- helpers ----------------

    private static String firstOrEmpty(List<String> list) {
        if (list == null || list.isEmpty()) return "";
        return list.get(0);
    }
}