package ru.study.core.model;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import ru.study.core.exception.ValidationException;

import java.time.Instant;

@Builder
@Getter
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public final class Account {
    @EqualsAndHashCode.Include
    private final Long id;
    private final EmailAddress email;
    private final String displayName;
    private final String imapHost;
    private final int imapPort;
    private final String smtpHost;
    private final int smtpPort;
    private final boolean useTls;
    private final Instant createdAt;

    public Account(Long id, EmailAddress email, String displayName,
                   String imapHost, int imapPort, String smtpHost, int smtpPort,
                   boolean useTls, Instant createdAt) {
        if (email == null) throw new ValidationException("Email is required");
        if (imapHost == null || imapHost.isBlank()) throw new ValidationException("IMAP host is required");
        if (smtpHost == null || smtpHost.isBlank()) throw new ValidationException("SMTP host is required");
        this.id = id;
        this.email = email;
        this.displayName = displayName;
        this.imapHost = imapHost;
        this.imapPort = imapPort;
        this.smtpHost = smtpHost;
        this.smtpPort = smtpPort;
        this.useTls = useTls;
        this.createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}