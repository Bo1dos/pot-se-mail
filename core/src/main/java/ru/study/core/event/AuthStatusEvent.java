package ru.study.core.event;

public final class AuthStatusEvent {
    private final Long accountId;
    private final AuthStatus status;
    private final String message;

    public AuthStatusEvent(Long accountId, AuthStatus status, String message) {
        this.accountId = accountId;
        this.status = status;
        this.message = message;
    }

    public Long getAccountId() { return accountId; }
    public AuthStatus getStatus() { return status; }
    public String getMessage() { return message; }

    public enum AuthStatus { SUCCESS, FAILURE, REQUIRES_ACTION }
}
