package ru.study.service.api;

import ru.study.core.exception.CoreException;
import ru.study.core.model.AttachmentReference;

import java.io.InputStream;

public interface AttachmentService {
    /**
     * Save attachment (stream). Returns AttachmentReference domain object.
     * Caller provides InputStream; implementation must consume it and close.
     */
    AttachmentReference saveAttachment(InputStream in, Long accountId, Long messageId, String filename, boolean storeInDb) throws CoreException;

    /**
     * Load attachment as InputStream (caller must close). If attachment is encrypted, decrypt with masterPassword.
     */
    InputStream loadAttachment(Long attachmentId, char[] masterPassword) throws CoreException;

    void deleteAttachment(Long attachmentId) throws CoreException;
}
