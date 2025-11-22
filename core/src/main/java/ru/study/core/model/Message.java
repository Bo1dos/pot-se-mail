package ru.study.core.model;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

@Builder
@Getter
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public final class Message {
    @EqualsAndHashCode.Include
    private final Long id;
    private final Long accountId;
    private final String folderName;
    private final EmailAddress from;
    private final List<EmailAddress> to;
    private final List<EmailAddress> cc;
    private final String subject;
    private final String snippet;
    private final Instant date;
    private final boolean seen;
    private final boolean encrypted;
    private final List<AttachmentReference> attachments;

    public Message(Long id, Long accountId, String folderName,
                   EmailAddress from, List<EmailAddress> to, List<EmailAddress> cc,
                   String subject, String snippet, Instant date,
                   boolean seen, boolean encrypted, List<AttachmentReference> attachments) {
        this.id = id;
        this.accountId = accountId;
        this.folderName = folderName;
        this.from = from;
        this.to = to == null ? Collections.emptyList() : List.copyOf(to);
        this.cc = cc == null ? Collections.emptyList() : List.copyOf(cc);
        this.subject = subject;
        this.snippet = snippet;
        this.date = date == null ? Instant.now() : date;
        this.seen = seen;
        this.encrypted = encrypted;
        this.attachments = attachments == null ? Collections.emptyList() : List.copyOf(attachments);
    }
}