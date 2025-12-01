package ru.study.service.dto;

import lombok.Builder;
import lombok.Value;

import java.io.InputStream;
import java.util.List;

@Value
@Builder
public class SendMessageDTO {
    String from;
    List<String> to;
    List<String> cc;
    List<String> bcc;
    String subject;
    String body;
    boolean html;
    List<OutgoingAttachmentDTO> attachments;
}