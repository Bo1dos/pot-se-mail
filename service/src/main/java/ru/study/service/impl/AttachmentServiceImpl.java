package ru.study.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.study.core.exception.CoreException;
import ru.study.core.exception.NotFoundException;
import ru.study.core.model.AttachmentReference;
import ru.study.persistence.entity.AttachmentEntity;
import ru.study.persistence.entity.MessageEntity;
import ru.study.persistence.mapper.AttachmentMapper;
import ru.study.persistence.repository.api.AttachmentRepository;
import ru.study.persistence.repository.api.MessageRepository;
import ru.study.persistence.repository.impl.AttachmentRepositoryImpl;
import ru.study.persistence.repository.impl.MessageRepositoryImpl;
import ru.study.persistence.util.EntityManagerFactoryProvider;
import ru.study.service.api.AttachmentService;
import ru.study.service.api.NotificationService;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;
import java.io.*;
import java.nio.file.*;
import java.util.UUID;

public class AttachmentServiceImpl implements AttachmentService {

    private static final Logger log = LoggerFactory.getLogger(AttachmentServiceImpl.class);

    private static final Path DEFAULT_ATTACH_DIR = Paths.get("./data/attachments");
    // 5 MB threshold for storing attachment in DB
    private static final long MAX_DB_ATTACHMENT_SIZE = 5_242_880L;

    private final Path attachmentsDir;
    private final NotificationService notificationService;

    public AttachmentServiceImpl(NotificationService notificationService) {
        this(notificationService, DEFAULT_ATTACH_DIR);
    }

    public AttachmentServiceImpl(NotificationService notificationService, Path attachmentsDir) {
        this.attachmentsDir = attachmentsDir == null ? DEFAULT_ATTACH_DIR : attachmentsDir;
        this.notificationService = notificationService;

        try {
            Files.createDirectories(this.attachmentsDir);
        } catch (IOException e) {
            throw new CoreException("Failed to create attachments directory: " + this.attachmentsDir, e);
        }
    }

    @Override
    public AttachmentReference saveAttachment(InputStream in, Long accountId, Long messageId, String filename, boolean storeInDb) throws CoreException {
        if (in == null) throw new CoreException("Input stream is null");
        if (messageId == null) throw new CoreException("messageId is required");

        String tmpName = "att-" + UUID.randomUUID() + ".tmp";
        Path tmpPath = attachmentsDir.resolve(tmpName);

        try {
            // write incoming stream to temporary file (streaming)
            try (OutputStream out = Files.newOutputStream(tmpPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                 BufferedInputStream bin = new BufferedInputStream(in)) {
                byte[] buf = new byte[8192];
                int r;
                while ((r = bin.read(buf)) != -1) out.write(buf, 0, r);
                out.flush();
            }

            long size = Files.size(tmpPath);

            // Decide storage target: DB only if requested AND small enough
            boolean storeInDbEffective = storeInDb && size <= MAX_DB_ATTACHMENT_SIZE;
            if (storeInDb && !storeInDbEffective) {
                log.warn("Requested storeInDb=true but attachment size {} > {}; falling back to file storage", size, MAX_DB_ATTACHMENT_SIZE);
            }

            EntityManager em = EntityManagerFactoryProvider.createEntityManager();
            AttachmentRepository attachmentRepository = new AttachmentRepositoryImpl(em);
            MessageRepository messageRepository = new MessageRepositoryImpl(em);

            EntityTransaction tx = em.getTransaction();
            try {
                tx.begin();

                MessageEntity message = messageRepository.findById(messageId)
                        .orElseThrow(() -> new NotFoundException("Message not found: id=" + messageId));

                AttachmentEntity entity = new AttachmentEntity();
                entity.setMessage(message);
                entity.setFilename(filename == null ? "unknown" : filename);
                entity.setContentType(null);
                entity.setSize(size);

                if (storeInDbEffective) {
                    byte[] data = Files.readAllBytes(tmpPath);
                    entity.setEncryptedBlob(data);
                    entity.setIv(null);
                    entity.setFilePath(null);
                    try { Files.deleteIfExists(tmpPath); } catch (IOException ignored) {}
                } else {
                    String safeName = UUID.randomUUID() + "_" + sanitizeFileName(entity.getFilename());
                    Path finalPath = attachmentsDir.resolve(safeName);
                    try {
                        Files.move(tmpPath, finalPath, StandardCopyOption.ATOMIC_MOVE);
                    } catch (AtomicMoveNotSupportedException amnse) {
                        Files.move(tmpPath, finalPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                    entity.setFilePath(finalPath.toString());
                    entity.setEncryptedBlob(null);
                    entity.setIv(null);
                }

                AttachmentEntity saved = attachmentRepository.save(entity);
                tx.commit();

                if (notificationService != null) {
                    notificationService.notifyInfo("Attachment saved: id=" + saved.getId());
                }

                return AttachmentMapper.toDomain(saved);
            } catch (Exception e) {
                if (tx.isActive()) tx.rollback();
                // cleanup temp
                try { Files.deleteIfExists(tmpPath); } catch (IOException ignored) {}
                throw new CoreException("Failed to save attachment: " + e.getMessage(), e);
            } finally {
                if (em != null && em.isOpen()) em.close();
            }

        } catch (IOException e) {
            try { Files.deleteIfExists(tmpPath); } catch (IOException ignored) {}
            throw new CoreException("IO error while saving attachment", e);
        }
    }

    @Override
    public InputStream loadAttachment(Long attachmentId, char[] masterPassword) throws CoreException {
        if (attachmentId == null) throw new CoreException("attachmentId is required");

        EntityManager em = EntityManagerFactoryProvider.createEntityManager();
        AttachmentRepository attachmentRepository = new AttachmentRepositoryImpl(em);
        try {
            AttachmentEntity entity = attachmentRepository.findById(attachmentId)
                    .orElseThrow(() -> new NotFoundException("Attachment not found: id=" + attachmentId));

            boolean storedInDb = entity.getEncryptedBlob() != null && entity.getEncryptedBlob().length > 0;
            if (storedInDb) {
                // return a copy of bytes (safe after closing EM)
                byte[] data = entity.getEncryptedBlob();
                return new ByteArrayInputStream(data);
            } else {
                String path = entity.getFilePath();
                if (path == null) throw new CoreException("Attachment has no file path and is not stored in DB");
                Path p = Paths.get(path);
                if (!Files.exists(p)) throw new NotFoundException("Attachment file not found: " + path);
                // close EM before returning file stream
                em.close();
                return Files.newInputStream(p, StandardOpenOption.READ);
            }
        } catch (IOException e) {
            throw new CoreException("Failed to open attachment stream: " + e.getMessage(), e);
        } finally {
            if (em != null && em.isOpen()) em.close();
        }
    }

    @Override
    public void deleteAttachment(Long attachmentId) throws CoreException {
        if (attachmentId == null) throw new CoreException("attachmentId is required");

        EntityManager em = EntityManagerFactoryProvider.createEntityManager();
        AttachmentRepository attachmentRepository = new AttachmentRepositoryImpl(em);
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();

            AttachmentEntity entity = attachmentRepository.findById(attachmentId)
                    .orElseThrow(() -> new NotFoundException("Attachment not found: id=" + attachmentId));

            if (entity.getFilePath() != null) {
                try {
                    Files.deleteIfExists(Paths.get(entity.getFilePath()));
                } catch (IOException e) {
                    if (notificationService != null) {
                        notificationService.notifyError("Failed to delete attachment file: " + entity.getFilePath(), e);
                    }
                    log.warn("Failed to delete file {}: {}", entity.getFilePath(), e.getMessage());
                }
            }

            attachmentRepository.delete(entity);
            tx.commit();

            if (notificationService != null) {
                notificationService.notifyInfo("Attachment deleted: id=" + attachmentId);
            }
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw new CoreException("Failed to delete attachment: " + e.getMessage(), e);
        } finally {
            if (em != null && em.isOpen()) em.close();
        }
    }

    private static String sanitizeFileName(String name) {
        if (name == null) return "unknown";
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    // optional no-op: service has no long-lived EM anymore
    public void close() { /* nothing to close here */ }
}
