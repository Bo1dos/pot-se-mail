package ru.study.service.api;

import ru.study.core.dto.KeyDTO;
import ru.study.core.exception.CoreException;
import ru.study.crypto.model.EncryptedBlob;

import java.security.PublicKey;
import java.util.Optional;

public interface KeyManagementService {
    /**
     * Generate RSA key pair, encrypt private key with masterPassword and persist.
     * Returns KeyDTO (metadata).
     */
    KeyDTO generateKeyPair(Long accountId, char[] masterPassword) throws CoreException;

    void storePrivateKey(Long accountId, EncryptedBlob encryptedPrivateKeyBlob, String keyId) throws CoreException;

    Optional<KeyDTO> findKeyById(Long keyId) throws CoreException;

    Optional<KeyDTO> findPublicKeyByEmail(String email) throws CoreException;

    String getPublicKeyPem(Long keyId) throws CoreException;

    /**
     * Decrypt private key; caller MUST wipe returned byte[] after use.
     */
    byte[] decryptPrivateKey(Long keyId, char[] masterPassword) throws CoreException;

    void importPublicKey(Long accountId, String pem) throws CoreException;
}
