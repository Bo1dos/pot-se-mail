package ru.study.service.dto;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class SendResultDTO {
    boolean success;
    String messageId; // server message-id if available
    String error;     // null if success
}
