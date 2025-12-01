package ru.study.mailadapter.model;

import java.util.List;
import java.util.Map;

public final class RawMail {
    private final long uid;
    private final Map<String, List<String>> headers;
    private final String bodyPlain;
    private final String bodyHtml;
    private final List<AttachmentDescriptor> attachments; // descriptors with partIndex

    public RawMail(long uid, Map<String, List<String>> headers,
                   String bodyPlain, String bodyHtml,
                   List<AttachmentDescriptor> attachments) {
        this.uid = uid;
        this.headers = headers;
        this.bodyPlain = bodyPlain;
        this.bodyHtml = bodyHtml;
        this.attachments = attachments;
    }

    public long getUid() { return uid; }
    public Map<String, List<String>> getHeaders() { return headers; }
    public String getBodyPlain() { return bodyPlain; }
    public String getBodyHtml() { return bodyHtml; }
    public List<AttachmentDescriptor> getAttachments() { return attachments; }
}
