package ru.study.mailadapter.api;

import ru.study.mailadapter.model.*;
import ru.study.mailadapter.exception.MailException;

import java.io.InputStream;
import java.util.List;

public interface MailAdapter {
    /**
     * Connects to mail servers using provided config. Password must be plaintext (service must decrypt).
     */
    void connect(AccountConfig config) throws MailException;

    /**
     * Disconnects / closes resources.
     */
    void disconnect();

    /**
     * List folders on server (e.g. INBOX, Sent).
     */
    List<MailFolder> listFolders() throws MailException;

    /**
     * Fetch headers from folder with UID > sinceUid. If sinceUid == 0 -> fetch all.
     * Limit can be used to restrict number of results (descending by date).
     */
    List<MailHeader> fetchHeaders(String folderName, long sinceUid, int limit) throws MailException;

    /**
     * Fetch full message by server UID. Returned RawMail includes attachment descriptors
     * with partIndex that can be used to open the stream.
     */
    RawMail fetchMessage(String folderName, long uid) throws MailException;

    /**
     * Open attachment stream by folder + uid + attachmentId (attachmentId corresponds to descriptor id).
     * Caller MUST close returned InputStream.
     */
    InputStream openAttachmentStream(String folderName, long uid, String attachmentId) throws MailException;

    /**
     * Send prepared outgoing mail (subject, body, recipients, attachments).
     */
    void send(RawOutgoingMail mail) throws MailException;
}
