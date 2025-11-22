package ru.study.core.event;

public final class SyncCompletedEvent {
    private final Long accountId;
    private final boolean success;
    private final String details; // краткое описание/ошибка

    public SyncCompletedEvent(Long accountId, boolean success, String details) {
        this.accountId = accountId;
        this.success = success;
        this.details = details;
    }

    public Long getAccountId() { return accountId; }
    public boolean isSuccess() { return success; }
    public String getDetails() { return details; }
}
