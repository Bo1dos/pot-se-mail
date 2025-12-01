package ru.study.mailadapter.model;

import java.time.Instant;

public final class MailHeader {
    private final long uid;
    private final String messageId;
    private final String from;
    private final String subject;
    private final Instant date;
    private final boolean seen;
    private final boolean hasAttachments;

    public MailHeader(long uid, String messageId, String from, String subject, Instant date,
                      boolean seen, boolean hasAttachments) {
        this.uid = uid;
        this.messageId = messageId;
        this.from = from;
        this.subject = subject;
        this.date = date;
        this.seen = seen;
        this.hasAttachments = hasAttachments;
    }

    public long getUid() { return uid; }
    public String getMessageId() { return messageId; }
    public String getFrom() { return from; }
    public String getSubject() { return subject; }
    public Instant getDate() { return date; }
    public boolean isSeen() { return seen; }
    public boolean isHasAttachments() { return hasAttachments; }
}
