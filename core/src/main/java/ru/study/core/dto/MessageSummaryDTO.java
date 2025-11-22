package ru.study.core.dto;

import java.time.Instant;

public record MessageSummaryDTO(
    Long id,
    String from,
    String subject,
    String snippet,
    Instant date,
    boolean seen,
    boolean encrypted,
    boolean hasAttachments
) {}
