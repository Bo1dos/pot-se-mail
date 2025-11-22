package ru.study.core.dto;

public record FolderDTO(
    Long id,
    Long accountId,
    String serverName,
    String localName,
    Long lastSyncUid
) {}
