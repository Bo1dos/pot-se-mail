package ru.study.core.dto;

public record AccountDTO(
    Long id,
    String email,
    String displayName,
    String imapServer,
    Integer imapPort,
    String smtpServer,
    Integer smtpPort,
    Boolean useTls
) {}
