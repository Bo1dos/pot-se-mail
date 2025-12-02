package ru.study.core.event;

import ru.study.core.dto.MessageDetailDTO;

public final class ComposeMessageEvent {
    private final Long accountId;
    private final MessageDetailDTO originalMessage; // may be null for new message

    public ComposeMessageEvent(Long accountId, MessageDetailDTO originalMessage) {
        this.accountId = accountId;
        this.originalMessage = originalMessage;
    }

    public Long getAccountId() { return accountId; }
    public MessageDetailDTO getOriginalMessage() { return originalMessage; }
}
