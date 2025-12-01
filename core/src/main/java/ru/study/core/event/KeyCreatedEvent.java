package ru.study.core.event;

import java.time.Instant;

public final class KeyCreatedEvent {
    private final Long keyId;
    private final Long accountId;
    private final Instant createdAt;

    public KeyCreatedEvent(Long keyId, Long accountId, Instant createdAt) {
        this.keyId = keyId;
        this.accountId = accountId;
        this.createdAt = createdAt;
    }

    public Long keyId() { return keyId; }
    public Long accountId() { return accountId; }
    public Instant createdAt() { return createdAt; }
}
