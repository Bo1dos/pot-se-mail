package ru.study.core.dto;

public record AttachmentMetaDTO(
    Long id,
    String fileName,
    String contentType,
    long size,
    boolean storedInDb, // true = blob, false = file path (hybrid)
    String filePath // may be null if storedInDb
) {}
