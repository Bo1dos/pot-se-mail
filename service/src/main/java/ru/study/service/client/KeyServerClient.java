package ru.study.service.client;

import ru.study.core.dto.KeyDTO;
import ru.study.core.exception.CoreException;

import java.util.Optional;

public interface KeyServerClient {
    /**
     * Lookup verified public key by email. Returns Optional.empty() if not found.
     */
    Optional<KeyDTO> findKeyByEmail(String email) throws CoreException;

    /**
     * Upload public key (returns created KeyDTO with keyId etc).
     */
    KeyDTO uploadPublicKey(String email, String publicKeyPem) throws CoreException;

    /**
     * Verify uploaded key by token (optional flow).
     */
    void verifyKeyByToken(String token) throws CoreException;
}
