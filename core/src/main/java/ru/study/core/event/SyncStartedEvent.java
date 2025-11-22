package ru.study.core.event;

public final class SyncStartedEvent {
    private final Long accountId;

    public SyncStartedEvent(Long accountId) { this.accountId = accountId; }
    public Long getAccountId() { return accountId; }
}
