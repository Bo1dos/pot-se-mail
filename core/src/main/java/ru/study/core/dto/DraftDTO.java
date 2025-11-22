package ru.study.core.dto;

import java.time.Instant;
import java.util.List;

public record DraftDTO(
    Long id,
    Long accountId,
    String subject,
    List<String> to,
    List<String> cc,
    String bodyHtml,
    Instant modifiedAt
) {}
