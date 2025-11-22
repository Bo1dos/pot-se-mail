package ru.study.core.dto;

import java.time.Instant;
import java.util.List;

public record MessageDetailDTO(
    Long id,
    Long accountId,
    String folderName,
    String from,
    List<String> to,
    List<String> cc,
    String subject,
    String bodyHtml,
    String bodyText,
    Instant date,
    Boolean seen,
    Boolean encrypted,
    List<AttachmentMetaDTO> attachments,
    Boolean signatureValid
) {}
