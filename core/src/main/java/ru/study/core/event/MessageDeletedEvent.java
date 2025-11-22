package ru.study.core.event;

public final class MessageDeletedEvent {
    private final Long accountId;
    private final Long messageId;

    public MessageDeletedEvent(Long accountId, Long messageId) {
        this.accountId = accountId;
        this.messageId = messageId;
    }

    public Long getAccountId() { return accountId; }
    public Long getMessageId() { return messageId; }
}
