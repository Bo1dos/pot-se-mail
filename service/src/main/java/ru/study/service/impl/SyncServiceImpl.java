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
import ru.study.persistence.entity.FolderEntity;
import ru.study.persistence.entity.MessageEntity;
import ru.study.persistence.mapper.MessageMapper;
import ru.study.persistence.mapper.FolderMapper;
import ru.study.persistence.repository.api.AttachmentRepository;
import ru.study.persistence.repository.api.FolderRepository;
import ru.study.persistence.repository.api.MessageRepository;
import ru.study.persistence.repository.impl.AttachmentRepositoryImpl;
import ru.study.persistence.repository.impl.FolderRepositoryImpl;
import ru.study.persistence.repository.impl.MessageRepositoryImpl;
import ru.study.persistence.util.EntityManagerFactoryProvider;
import ru.study.service.api.AccountService;
import ru.study.service.api.NotificationService;
import ru.study.service.api.SyncService;
import ru.study.service.dto.SyncResult;
import ru.study.mailadapter.model.AccountConfig;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;
import java.util.List;
import java.util.concurrent.*;

public class SyncServiceImpl implements SyncService {

    private final MailAdapter mailAdapter;
    private final NotificationService notificationService;
    private final EventBus eventBus;
    private final AccountService accountService;
    private final ScheduledExecutorService scheduler;

    public SyncServiceImpl(MailAdapter mailAdapter,
                           NotificationService notificationService,
                           ru.study.core.event.bus.EventBus eventBus,
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
                for (FolderEntity f : folders) {
                    try {
                        // this syncFolder implementation will assume adapter is already connected
                        syncFolderInternal(em, accountId, f.getServerName());
                    } catch (Exception ex) {
                        success = false;
                        details = "error during folder sync: " + ex.getMessage();
                        notificationService.notifyError("Sync folder failed: " + f.getServerName(), ex);
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
                            notificationService.notifyError("Auto-sync account failed: " + aid, ex);
                        }
                    }
                } finally {
                    if (em.isOpen()) em.close();
                }
            } catch (Throwable t) {
                notificationService.notifyError("Auto-sync fatal error", t);
            }
        }, 0, 5, TimeUnit.MINUTES);
        notificationService.notifyInfo("Auto-sync started");
    }

    @Override
    public void stopAutoSync() {
        scheduler.shutdownNow();
        notificationService.notifyInfo("Auto-sync stopped");
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
            notificationService.notifyError("Sync folder failed: " + folderName, e);
            return SyncResult.builder().newMessages(0).errors(List.of("error: " + e.getMessage())).build();
        } finally {
            try { mailAdapter.disconnect(); } catch (Exception ignored) {}
        }
    }

    /**
     * Internal: assumes mailAdapter is already connected for the account.
     * Uses provided EntityManager (caller must open/close).
     * @throws MailException 
     */
    private void syncFolderInternal(EntityManager em, Long accountId, String folderName) throws MailException {
        FolderRepository folderRepo = new FolderRepositoryImpl(em);
        MessageRepository messageRepo = new MessageRepositoryImpl(em);
        AttachmentRepository attachmentRepo = new AttachmentRepositoryImpl(em);

        FolderEntity folderEntity = folderRepo.findByAccountAndServerName(accountId, folderName)
                .orElseThrow(() -> new NotFoundException("Folder not found: " + folderName));

        long sinceUid = folderEntity.getLastSyncUid() == null ? 0L : folderEntity.getLastSyncUid();

        List<MailHeader> headers = mailAdapter.fetchHeaders(folderName, sinceUid, 200);

        int newCount = 0;
        for (MailHeader h : headers) {
            String serverUid = String.valueOf(h.getUid());
            boolean exists = messageRepo.findByAccountAndServerUid(accountId, serverUid).isPresent();
            if (exists) continue;

            RawMail raw = mailAdapter.fetchMessage(folderName, h.getUid());

            EntityTransaction tx = em.getTransaction();
            try {
                tx.begin();

                MessageEntity me = new MessageEntity();
                me.setAccountId(accountId);
                me.setFolder(folderEntity);
                me.setServerUid(serverUid);
                me.setSubject(h.getSubject());
                me.setSender(h.getFrom());
                me.setRecipients(null);
                me.setCc(null);
                me.setSentDate(java.time.OffsetDateTime.now());
                me.setIsEncrypted(Boolean.FALSE);
                me.setEncryptedBodyBlob(null);
                me.setSignatureBlob(null);
                me.setIsSeen(h.isSeen());
                me.setIsDeleted(Boolean.FALSE);

                MessageEntity saved = messageRepo.save(me);

                if (raw.getAttachments() != null && !raw.getAttachments().isEmpty()) {
                    for (AttachmentDescriptor ad : raw.getAttachments()) {
                        var att = new ru.study.persistence.entity.AttachmentEntity();
                        att.setMessage(saved);
                        att.setFilename(ad.getFileName());
                        att.setContentType(ad.getContentType());
                        att.setSize(ad.getSize() < 0 ? null : ad.getSize());
                        att.setFilePath(null);
                        att.setEncryptedBlob(null);
                        attachmentRepo.save(att);
                    }
                }

                folderEntity.setLastSyncUid(Math.max(folderEntity.getLastSyncUid() == null ? 0L : folderEntity.getLastSyncUid(), h.getUid()));
                folderRepo.save(folderEntity);

                tx.commit();

                Message domain = MessageMapper.toDomain(saved);
                MessageSummaryDTO summary = MessageMapper.toSummaryDto(domain);
                eventBus.publish(new NewMessageEvent(summary));

                newCount++;
            } catch (Exception e) {
                if (tx.isActive()) tx.rollback();
                notificationService.notifyError("Failed to persist incoming message uid=" + h.getUid(), e);
            }
        }

        String details = "synced folder: " + folderName + ", new=" + newCount;
        notificationService.notifyInfo(details);
        // optional: could return per-folder SyncResult; here we just publish info
    }
}
