package ru.study.core.dto;

import java.time.Instant;

public record KeyDTO(
    Long id,
    Long accountId,
    String keyId,
    String publicKeyPem,
    boolean privateKeyStored,
    Instant createdAt
) {}
