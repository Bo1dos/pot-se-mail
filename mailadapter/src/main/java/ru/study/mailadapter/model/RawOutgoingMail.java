package ru.study.mailadapter.model;

import java.io.InputStream;
import java.util.List;

public final class RawOutgoingMail {
    private final String from;
    private final List<String> to;
    private final List<String> cc;
    private final List<String> bcc;
    private final String subject;
    private final String body; // HTML or plain depends on isHtml
    private final boolean isHtml;
    private final List<OutgoingAttachment> attachments; // may be empty

    public RawOutgoingMail(String from, List<String> to, List<String> cc, List<String> bcc,
                           String subject, String body, boolean isHtml, List<OutgoingAttachment> attachments) {
        this.from = from;
        this.to = to;
        this.cc = cc;
        this.bcc = bcc;
        this.subject = subject;
        this.body = body;
        this.isHtml = isHtml;
        this.attachments = attachments;
    }

    public String getFrom() { return from; }
    public List<String> getTo() { return to; }
    public List<String> getCc() { return cc; }
    public List<String> getBcc() { return bcc; }
    public String getSubject() { return subject; }
    public String getBody() { return body; }
    public boolean isHtml() { return isHtml; }
    public List<OutgoingAttachment> getAttachments() { return attachments; }
}

