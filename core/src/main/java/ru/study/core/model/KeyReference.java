package ru.study.core.model;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.time.Instant;

@Builder
@Getter
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public final class KeyReference {
    @EqualsAndHashCode.Include
    private final Long id;
    private final Long accountId;
    private final String keyId;
    private final String publicKeyPem;
    private final boolean privateKeyStored;
    private final Instant createdAt;

    public KeyReference(Long id, Long accountId, String keyId, String publicKeyPem, 
                       boolean privateKeyStored, Instant createdAt) {
        this.id = id;
        this.accountId = accountId;
        this.keyId = keyId;
        this.publicKeyPem = publicKeyPem;
        this.privateKeyStored = privateKeyStored;
        this.createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}