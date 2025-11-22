package ru.study.core.event;

import ru.study.core.dto.AttachmentMetaDTO;

public final class AttachmentSavedEvent {
    private final Long messageId;
    private final AttachmentMetaDTO attachment;

    public AttachmentSavedEvent(Long messageId, AttachmentMetaDTO attachment) {
        this.messageId = messageId;
        this.attachment = attachment;
    }

    public Long getMessageId() { return messageId; }
    public AttachmentMetaDTO getAttachment() { return attachment; }
}
