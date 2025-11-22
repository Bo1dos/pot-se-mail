package ru.study.core.event.bus;

import ru.study.core.dto.MessageSummaryDTO;

public class NewMessageEvent {
    private final MessageSummaryDTO message;

    public NewMessageEvent(MessageSummaryDTO message) { this.message = message; }
    public MessageSummaryDTO getMessage() { return message; }
}
