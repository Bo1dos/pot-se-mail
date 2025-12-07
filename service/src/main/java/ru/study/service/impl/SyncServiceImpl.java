package ru.study.service.impl;

import ru.study.core.dto.MessageSummaryDTO;
import ru.study.core.event.NewMessageEvent;
import ru.study.core.event.SyncCompletedEvent;
import ru.study.core.event.SyncStartedEvent;
import ru.study.core.event.bus.EventBus;
import ru.study.core.exception.CoreException;
import ru.study.core.exception.NotFoundException;
import ru.study.core.model.Message;
import ru.study.mailadapter.api.MailAdapter;
import ru.study.mailadapter.exception.MailException;
import ru.study.mailadapter.model.AttachmentDescriptor;
import ru.study.mailadapter.model.MailHeader;
import ru.study.mailadapter.model.RawMail;
import ru.study.persistence.entity.AttachmentEntity;
import ru.study.persistence.entity.FolderEntity;
import ru.study.persistence.entity.MessageEntity;
import ru.study.persistence.entity.MessageWrappedKeyEntity;
import ru.study.persistence.mapper.MessageMapper;
import ru.study.persistence.repository.api.AccountRepository;
import ru.study.persistence.repository.api.AttachmentRepository;
import ru.study.persistence.repository.api.FolderRepository;
import ru.study.persistence.repository.api.MessageRepository;
import ru.study.persistence.repository.api.MessageWrappedKeyRepository;
import ru.study.persistence.repository.impl.AccountRepositoryImpl;
import ru.study.persistence.repository.impl.AttachmentRepositoryImpl;
import ru.study.persistence.repository.impl.FolderRepositoryImpl;
import ru.study.persistence.repository.impl.MessageRepositoryImpl;
import ru.study.persistence.repository.impl.MessageWrappedKeyRepositoryImpl;
import ru.study.persistence.util.EntityManagerFactoryProvider;
import ru.study.service.api.AccountService;
import ru.study.service.api.NotificationService;
import ru.study.service.api.SyncService;
import ru.study.service.dto.SyncResult;
import ru.study.mailadapter.model.AccountConfig;

import jakarta.persistence.EntityManager;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

public class SyncServiceImpl implements SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncServiceImpl.class);

    private final MailAdapter mailAdapter;
    private final NotificationService notificationService;
    private final EventBus eventBus;
    private final AccountService accountService;
    private final ScheduledExecutorService scheduler;

    public SyncServiceImpl(MailAdapter mailAdapter,
                           NotificationService notificationService,
                           EventBus eventBus,
                           AccountService accountService) {
        this.mailAdapter = mailAdapter;
        this.notificationService = notificationService;
        this.eventBus = eventBus;
        this.accountService = accountService;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sync-service-scheduler");
            t.setDaemon(true);
            return t;
        });
    }
    
    @Override
    public void syncAccount(Long accountId) throws CoreException {
        eventBus.publish(new SyncStartedEvent(accountId));
        boolean success = true;
        String details = "ok";

        // get config (throws if no master password / credential)
        AccountConfig cfg = accountService.getAccountConfig(accountId);

        try {
            // connect for this account
            mailAdapter.connect(cfg);

            // iterate folders and sync (folder repos need EM)
            EntityManager em = EntityManagerFactoryProvider.createEntityManager();
            try {
                FolderRepository folderRepo = new FolderRepositoryImpl(em);
                List<FolderEntity> folders = folderRepo.findByAccountId(accountId);

                // If no local folders found — try to fetch server folders and create local entries (MVP)
                if (folders == null || folders.isEmpty()) {
                    log.debug("No local folders found for account {}, will query server folders and create local entries", accountId);
                    try {
                        List<ru.study.mailadapter.model.MailFolder> serverFolders = mailAdapter.listFolders();
                        if (serverFolders != null && !serverFolders.isEmpty()) {
                            for (ru.study.mailadapter.model.MailFolder mf : serverFolders) {
                                try {
                                    FolderEntity fe = new FolderEntity();
                                    fe.setAccountId(accountId);

                                    // try to use common getters; fallback to toString()
                                    String srvName = null;
                                    try {
                                        srvName = (String) mf.getClass().getMethod("getFullName").invoke(mf);
                                    } catch (NoSuchMethodException nsme) {
                                        try {
                                            srvName = (String) mf.getClass().getMethod("getName").invoke(mf);
                                        } catch (Exception ignore) {
                                            srvName = mf.toString();
                                        }
                                    }

                                    fe.setServerName(srvName);
                                    fe.setLocalName(srvName);
                                    fe.setLastSyncUid(null);
                                    

                                    // call repo save (we DO NOT open tx manually here by your request)
                                    folderRepo.save(fe);
                                    log.info("Requested creation of local folder record: {} for account {}", srvName, accountId);
                                } catch (Exception feEx) {
                                    log.warn("Failed to create local folder entry for folder {}: {}", mf, feEx.getMessage());
                                }
                            }
                            // reload list from DB to get managed entities / IDs (may depend on repo tx behavior)
                            folders = folderRepo.findByAccountId(accountId);
                        } else {
                            log.debug("Server returned no folders for account {}", accountId);
                        }
                    } catch (Exception lfEx) {
                        log.warn("Failed to list folders from server for account {}: {}", accountId, lfEx.getMessage());
                        notificationService.notifyError("Failed to list server folders for account " + accountId, lfEx);
                    }
                }

                // then continue with existing loop over folders
                if (folders != null) {
                    for (FolderEntity f : folders) {
                        try {
                            log.debug("Syncing folder {} for account {}", f.getServerName(), accountId);
                            syncFolderInternal(em, accountId, f.getServerName());
                        } catch (Exception ex) {
                            success = false;
                            details = "error during folder sync: " + ex.getMessage();
                            notificationService.notifyError("Sync folder failed: " + f.getServerName(), ex);
                        }
                    }
                }

            } finally {
                if (em.isOpen()) em.close();
            }

        } catch (Exception e) {
            success = false;
            details = "syncAccount failed: " + e.getMessage();
            notificationService.notifyError("Sync account failed: " + accountId, e);
            throw new CoreException("Sync account failed: " + e.getMessage(), e);
        } finally {
            // always disconnect the adapter for this account
            try { mailAdapter.disconnect(); } catch (Exception ignored) {}
            eventBus.publish(new SyncCompletedEvent(accountId, success, details));
        }
    }

    @Override
    public void startAutoSync() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                EntityManager em = EntityManagerFactoryProvider.createEntityManager();
                try {
                    List<Long> accountIds = em.createQuery("SELECT a.id FROM ru.study.persistence.entity.AccountEntity a", Long.class)
                                              .getResultList();
                    for (Long aid : accountIds) {
                        try {
                            syncAccount(aid);
                        } catch (Exception ex) {
                            log.warn("Auto-sync account {} failed: {}", aid, ex.getMessage(), ex);
                            notificationService.notifyError("Auto-sync account failed: " + aid, ex);
                        }
                    }
                } finally {
                    if (em.isOpen()) em.close();
                }
            } catch (Throwable t) {
                log.error("Auto-sync fatal error: {}", t.getMessage(), t);
                notificationService.notifyError("Auto-sync fatal error", t);
            }
        }, 0, 5, TimeUnit.MINUTES);
        notificationService.notifyInfo("Auto-sync started");
        log.info("Auto-sync scheduler started (interval: 5 minutes)");
    }

    @Override
    public void stopAutoSync() {
        scheduler.shutdownNow();
        notificationService.notifyInfo("Auto-sync stopped");
        log.info("Auto-sync scheduler stopped");
    }

    /**
     * Public API: sync single folder for account. This method will connect/disconnect the adapter itself.
     */
    @Override
    public SyncResult syncFolder(Long accountId, String folderName) throws CoreException {
        AccountConfig cfg = accountService.getAccountConfig(accountId);
        try {
            mailAdapter.connect(cfg);
            EntityManager em = EntityManagerFactoryProvider.createEntityManager();
            try {
                syncFolderInternal(em, accountId, folderName);
                String details = "synced folder: " + folderName;
                notificationService.notifyInfo(details);
                return SyncResult.builder().newMessages(0).errors(List.of(details)).build(); // newMessages filled inside syncFolderInternal if needed
            } finally {
                if (em.isOpen()) em.close();
            }
        } catch (NotFoundException nf) {
            throw nf;
        } catch (Exception e) {
            log.error("Sync folder {} for account {} failed: {}", folderName, accountService, e.getMessage(), e);
            notificationService.notifyError("Sync folder failed: " + folderName, e);
            return SyncResult.builder().newMessages(0).errors(List.of("error: " + e.getMessage())).build();
        } finally {
            try {
                mailAdapter.disconnect();
            } catch (Exception ignored) {
                log.debug("Ignoring exception on mailAdapter.disconnect(): {}", ignored.getMessage());
            }
        }
    }

    /**
     * Internal: assumes mailAdapter is already connected for the account.
     * Uses provided EntityManager (caller must open/close).
     * No explicit transactions here — repositories are expected to handle them.
     * @throws MailException 
     */
    private void syncFolderInternal(EntityManager em, Long accountId, String folderName) throws MailException {
        FolderRepository folderRepo = new FolderRepositoryImpl(em);
        MessageRepository messageRepo = new MessageRepositoryImpl(em);
        AttachmentRepository attachmentRepo = new AttachmentRepositoryImpl(em);
        MessageWrappedKeyRepository wrappedKeyRepo = new MessageWrappedKeyRepositoryImpl(em);
        AccountRepository accountRepo = new AccountRepositoryImpl(em); // Добавляем

        // Получаем email текущего аккаунта
        String myEmail = accountRepo.findById(accountId)
            .map(acc -> acc.getEmail())
            .orElse(null);
        
        if (myEmail == null) {
            log.error("Cannot find email for account {}", accountId);
            return;
        }

        FolderEntity folderEntity = folderRepo.findByAccountAndServerName(accountId, folderName)
                .orElseThrow(() -> new NotFoundException("Folder not found: " + folderName));

        long sinceUid = folderEntity.getLastSyncUid() == null ? 0L : folderEntity.getLastSyncUid();

        log.debug("Fetching headers for account={}, folder={} sinceUid={}", accountId, folderName, sinceUid);
        List<MailHeader> headers = mailAdapter.fetchHeaders(folderName, sinceUid, 200);

        int newCount = 0;
        for (MailHeader h : headers) {
            String serverUid = String.valueOf(h.getUid());
            boolean exists = messageRepo.findByAccountAndServerUid(accountId, serverUid).isPresent();
            if (exists) {
                log.debug("Message with serverUid={} already exists for account {}, skipping", serverUid, accountId);
                continue;
            }

            RawMail raw = mailAdapter.fetchMessage(folderName, h.getUid());

            try {
                MessageEntity me = new MessageEntity();
                me.setAccountId(accountId);
                me.setFolder(folderEntity);
                me.setServerUid(serverUid);
                me.setSubject(h.getSubject());
                me.setSender(h.getFrom());
                me.setRecipients(null);
                me.setCc(null);
                me.setSentDate(java.time.OffsetDateTime.now());

                me.setIsEncrypted(Boolean.FALSE); // default, возможно станет TRUE ниже
                String bodyTextCandidate = raw.getBodyPlain() == null ? raw.getBodyHtml() : raw.getBodyPlain();
                
                if (bodyTextCandidate != null) {
                    // Trim to avoid whitespace interfering with base64 detection
                    String trimmed = bodyTextCandidate.trim();
                    try {
                        if (isEncryptedContent(trimmed)) {
                            // decoded bytes should be exactly the EncryptedBlob-encoded bytes (alg|iv|ct as UTF-8 bytes)
                            byte[] decoded = Base64.getDecoder().decode(trimmed);
                            me.setEncryptedBodyBlob(decoded); // store raw encoded EncryptedBlob bytes
                            me.setIsEncrypted(Boolean.TRUE);
                            log.debug("Detected encrypted message body (Base64 EncryptedBlob). Stored decoded blob, message will be marked encrypted.");
                        } else {
                            // plain text/html body
                            me.setEncryptedBodyBlob(bodyTextCandidate.getBytes(StandardCharsets.UTF_8));
                            me.setIsEncrypted(Boolean.FALSE);
                        }
                    } catch (IllegalArgumentException iae) {
                        // Base64 decode failed -> treat as plain text
                        log.debug("Body content is not valid Base64: treat as plaintext for message uid={}", serverUid);
                        me.setEncryptedBodyBlob(bodyTextCandidate.getBytes(StandardCharsets.UTF_8));
                        me.setIsEncrypted(Boolean.FALSE);
                    }
                } else {
                    me.setEncryptedBodyBlob(null);
                    me.setIsEncrypted(Boolean.FALSE);
                }

                me.setSignatureBlob(null);
                me.setIsSeen(h.isSeen());
                me.setIsDeleted(Boolean.FALSE);

                MessageEntity saved = messageRepo.save(me); // repo expected to handle tx

                // Переменная для хранения найденных ключей
                Map<String, byte[]> foundKeys = new HashMap<>();
                
                if (raw.getAttachments() != null && !raw.getAttachments().isEmpty()) {
                    final long ATTACH_DB_THRESHOLD = 5_242_880L; // 5MB
                    
                    for (AttachmentDescriptor ad : raw.getAttachments()) {
                        try {
                            InputStream attStream = null;
                            try {
                                attStream = mailAdapter.openAttachmentStream(folderName, h.getUid(), ad.getId());
                            } catch (Exception e) {
                                log.warn("Cannot open attachment stream for uid={}, attachmentId={} : {}", 
                                        h.getUid(), ad.getId(), e.getMessage());
                                attStream = null;
                            }

                            AttachmentEntity ae = new AttachmentEntity();
                            ae.setMessage(saved);
                            ae.setFilename(ad.getFileName() == null ? "unknown" : ad.getFileName());
                            ae.setContentType(ad.getContentType());
                            ae.setSize(ad.getSize() < 0 ? null : ad.getSize());
                            ae.setFilePath(null);
                            ae.setEncryptedBlob(null);
                            ae.setIv(null);

                            // Проверяем, не является ли это файлом с ключами
                            boolean isKeysFile = ad.getFileName() != null && 
                                                ad.getFileName().equals("encryption_keys.json");
                            
                            if (attStream == null) {
                                attachmentRepo.save(ae);
                                log.warn("Saved attachment metadata only (no stream) for message UID {} attachment {}", 
                                        h.getUid(), ad.getId());
                                continue;
                            }

                            if (isKeysFile) {
                                // Читаем JSON с ключами
                                try {
                                    byte[] jsonData = attStream.readAllBytes();
                                    String jsonStr = new String(jsonData, StandardCharsets.UTF_8);
                                    
                                    ObjectMapper mapper = new ObjectMapper();
                                    Map<String, String> keysMap = mapper.readValue(
                                        jsonStr, 
                                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {}
                                    );
                                    
                                    // Декодируем все ключи из Base64
                                    for (Map.Entry<String, String> entry : keysMap.entrySet()) {
                                        try {
                                            byte[] decodedKey = Base64.getDecoder().decode(entry.getValue());
                                            foundKeys.put(entry.getKey(), decodedKey);
                                            log.debug("Found wrapped key for recipient: {}", entry.getKey());
                                        } catch (Exception e) {
                                            log.warn("Failed to decode key for recipient {}: {}", 
                                                    entry.getKey(), e.getMessage());
                                        }
                                    }
                                    
                                    // Сохраняем сам файл как обычное вложение
                                    if (jsonData.length <= ATTACH_DB_THRESHOLD) {
                                        ae.setEncryptedBlob(jsonData);
                                        ae.setSize((long) jsonData.length);
                                    } else {
                                        final java.nio.file.Path attachmentsDir = java.nio.file.Paths.get("./data/attachments");
                                        try { java.nio.file.Files.createDirectories(attachmentsDir); } catch (Exception ignored) {}
                                        
                                        String sanitized = ad.getFileName().replaceAll("[^a-zA-Z0-9._-]", "_");
                                        java.nio.file.Path finalPath = attachmentsDir.resolve(
                                            java.util.UUID.randomUUID() + "_" + sanitized);
                                        
                                        java.nio.file.Files.write(finalPath, jsonData);
                                        ae.setFilePath(finalPath.toString());
                                        ae.setSize((long) jsonData.length);
                                    }
                                    
                                    attachmentRepo.save(ae);
                                    log.info("Processed encryption_keys.json for message UID {}, found {} keys", 
                                            h.getUid(), keysMap.size());
                                    
                                } catch (Exception e) {
                                    log.error("Failed to parse encryption_keys.json for message UID {}: {}", 
                                            h.getUid(), e.getMessage());
                                    // Сохраняем как обычное вложение
                                    processRegularAttachment(ae, attStream, attachmentRepo, ATTACH_DB_THRESHOLD);
                                }
                                continue;
                            }

                            // Обычное вложение
                            processRegularAttachment(ae, attStream, attachmentRepo, ATTACH_DB_THRESHOLD);
                            
                        } catch (Exception attEx) {
                            log.warn("Failed to persist attachment for message {}: {}", 
                                    serverUid, attEx.getMessage(), attEx);
                            notificationService.notifyError("Failed to persist attachment for message " + serverUid, attEx);
                        }
                    }
                }

                // Сохраняем найденные wrapped keys в БД
                if (!foundKeys.isEmpty() && me.getIsEncrypted()) {
                    for (Map.Entry<String, byte[]> entry : foundKeys.entrySet()) {
                        try {
                            MessageWrappedKeyEntity wk = new MessageWrappedKeyEntity();
                            wk.setMessage(saved);
                            wk.setRecipient(entry.getKey());
                            wk.setWrappedBlob(entry.getValue());
                            wrappedKeyRepo.save(wk);
                            log.debug("Saved wrapped key for recipient: {}", entry.getKey());
                        } catch (Exception e) {
                            log.error("Failed to save wrapped key for recipient {}: {}", 
                                    entry.getKey(), e.getMessage());
                        }
                    }
                    log.info("Saved {} wrapped keys for message UID {}", foundKeys.size(), h.getUid());
                }

                // Обновляем флаг isEncrypted, если нашли ключи
                if (!foundKeys.isEmpty() && !me.getIsEncrypted()) {
                    me.setIsEncrypted(Boolean.TRUE);
                    messageRepo.save(me);
                    log.debug("Updated message UID {} to encrypted=true (found wrapped keys)", h.getUid());
                }

                // update folder last sync uid and persist
                try {
                    long currentLast = folderEntity.getLastSyncUid() == null ? 0L : folderEntity.getLastSyncUid();
                    folderEntity.setLastSyncUid(Math.max(currentLast, h.getUid()));
                    folderRepo.save(folderEntity);
                } catch (Exception fex) {
                    log.warn("Failed to update folder lastSyncUid for {}: {}", folderName, fex.getMessage(), fex);
                    notificationService.notifyError("Failed to update folder sync state: " + folderName, fex);
                }

                Message domain = MessageMapper.toDomain(saved);
                MessageSummaryDTO summary = MessageMapper.toSummaryDto(domain);
                eventBus.publish(new NewMessageEvent(summary));

                newCount++;
                log.info("New message persisted account={}, folder={}, uid={}, encrypted={}, keysFound={}", 
                        accountId, folderName, serverUid, saved.getIsEncrypted(), foundKeys.size());
            } catch (Exception e) {
                log.warn("Failed to persist incoming message uid={} for account {} folder {}: {}", 
                        h.getUid(), accountId, folderName, e.getMessage(), e);
                notificationService.notifyError("Failed to persist incoming message uid=" + h.getUid(), e);
            }
        }

        String details = "synced folder: " + folderName + ", new=" + newCount;
        notificationService.notifyInfo(details);
        log.info(details);
    }

    // Вспомогательный метод для обработки обычных вложений
    private void processRegularAttachment(AttachmentEntity ae, InputStream attStream, 
                                        AttachmentRepository repo, long threshold) throws Exception {
        boolean storeInDb = (ae.getSize() == null || ae.getSize() <= threshold);
        
        if (storeInDb) {
            byte[] data = attStream.readAllBytes();
            ae.setEncryptedBlob(data);
            ae.setFilePath(null);
            ae.setSize((long) data.length);
            repo.save(ae);
            log.debug("Persisted small attachment to DB: {} ({} bytes)", ae.getFilename(), data.length);
        } else {
            final java.nio.file.Path attachmentsDir = java.nio.file.Paths.get("./data/attachments");
            try { java.nio.file.Files.createDirectories(attachmentsDir); } catch (Exception ignored) {}

            String rawName = ae.getFilename() == null ? "unknown" : ae.getFilename();
            String sanitized = rawName.replaceAll("[^a-zA-Z0-9._-]", "_");
            java.nio.file.Path finalPath = attachmentsDir.resolve(java.util.UUID.randomUUID() + "_" + sanitized);

            try (OutputStream os = java.nio.file.Files.newOutputStream(finalPath)) {
                byte[] buf = new byte[8192];
                int r;
                long written = 0;
                while ((r = attStream.read(buf)) != -1) { 
                    os.write(buf, 0, r); 
                    written += r; 
                }
                ae.setFilePath(finalPath.toString());
                ae.setSize(written);
                repo.save(ae);
                log.debug("Persisted large attachment to FS: {} ({} bytes) -> {}", 
                        ae.getFilename(), written, finalPath);
            }
        }
    }
    

    public static boolean isEncryptedContent(String content) {
        if (content == null || content.trim().isEmpty()) {
            return false;
        }
        
        String trimmed = content.trim();
        
        // 1. Проверяем, что это валидный Base64
        if (!isValidBase64(trimmed)) {
            return false;
        }
        
        try {
            byte[] decoded = Base64.getDecoder().decode(trimmed);
            String decodedStr = new String(decoded, StandardCharsets.UTF_8);
            
            // 2. Проверяем формат EncryptedBlobCodec
            // Формат: "Algorithm|base64(IV)|base64(CipherText)"
            String[] parts = decodedStr.split("\\|", 3);
            if (parts.length != 3) {
                return false;
            }
            
            // 3. Проверяем, что алгоритм поддерживается
            String algorithm = parts[0];
            return algorithm.startsWith("DES/") || 
                algorithm.startsWith("AES/") || 
                algorithm.startsWith("RSA/");
            
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isValidBase64(String str) {
        try {
            Base64.getDecoder().decode(str);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
