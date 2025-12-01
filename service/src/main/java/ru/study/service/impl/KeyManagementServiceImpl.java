package ru.study.service.impl;

import ru.study.core.dto.KeyDTO;
import ru.study.core.exception.CoreException;
import ru.study.persistence.entity.AccountEntity;
import ru.study.persistence.entity.KeyEntity;
import ru.study.persistence.repository.api.AccountRepository;
import ru.study.persistence.repository.api.KeyRepository;
import ru.study.crypto.api.AsymmetricCipher;
import ru.study.crypto.api.KeyManagement;
import ru.study.crypto.model.EncryptedBlob;
import ru.study.crypto.util.EncryptedBlobCodec;
import ru.study.crypto.util.PemUtils;
import ru.study.crypto.util.ZeroUtils;
import ru.study.service.api.KeyManagementService;
import ru.study.core.event.KeyCreatedEvent;
import ru.study.core.event.bus.EventBus;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public class KeyManagementServiceImpl implements KeyManagementService {

    private final KeyRepository keyRepository;
    private final AccountRepository accountRepository;
    private final AsymmetricCipher rsa;
    private final KeyManagement keyMgmt;
    private final EventBus eventBus;

    private static final int DEFAULT_RSA_BITS = 2048;
    private static final int KDF_ITERATIONS = 100_000;
    private static final int SALT_LEN = 16;

    public KeyManagementServiceImpl(
        KeyRepository keyRepository,
        AccountRepository accountRepository,
        AsymmetricCipher rsa,
        KeyManagement keyMgmt,
        EventBus eventBus
    ) {
        this.keyRepository = keyRepository;
        this.accountRepository = accountRepository;
        this.rsa = rsa;
        this.keyMgmt = keyMgmt;
        this.eventBus = eventBus;
    }

    @Override
    public KeyDTO generateKeyPair(Long accountId, char[] masterPassword) throws CoreException {
        byte[] salt = null;
        byte[] kek = null;
        byte[] privateEncoded = null;
        try {
            // generate RSA pair
            KeyPair kp = rsa.generateKeyPair(DEFAULT_RSA_BITS);
            PrivateKey priv = kp.getPrivate();

            String pubPem = PemUtils.toPemPublicKey(kp.getPublic());
            privateEncoded = priv.getEncoded();

            // derive KEK and encrypt private key
            salt = randomBytes(SALT_LEN);
            kek = keyMgmt.deriveKEK(masterPassword, salt, KDF_ITERATIONS);
            EncryptedBlob blob = keyMgmt.encryptPrivateKey(privateEncoded, kek);
            byte[] blobBytes = EncryptedBlobCodec.encodeToBytes(blob);

            // persist
            AccountEntity acc = accountRepository.findById(accountId)
                .orElseThrow(() -> new CoreException("Account not found: " + accountId));
            KeyEntity entity = new KeyEntity();
            entity.setAccount(acc);
            entity.setPublicKeyPem(pubPem);
            entity.setPrivateKeyBlob(blobBytes);
            entity.setKeyIv(blob.iv());
            entity.setKeySalt(Arrays.copyOf(salt, salt.length));
            entity.setCreatedAt(OffsetDateTime.now());
            KeyEntity saved = keyRepository.save(entity);

            KeyDTO dto = toDto(saved);
            // publish event if needed
            eventBus.publish(new KeyCreatedEvent(saved.getId(), accountId, saved.getCreatedAt().toInstant()));
            return dto;
        } catch (CoreException e) {
            throw e;
        } catch (Exception e) {
            throw new CoreException("generateKeyPair failed", e);
        } finally {
            if (privateEncoded != null) ZeroUtils.wipe(privateEncoded);
            if (kek != null) ZeroUtils.wipe(kek);
            if (salt != null) ZeroUtils.wipe(salt);
        }
    }

    @Override
    public void storePrivateKey(Long accountId, EncryptedBlob encryptedPrivateKeyBlob, String keyId) throws CoreException {
        try {
            AccountEntity acc = accountRepository.findById(accountId)
                .orElseThrow(() -> new CoreException("Account not found: " + accountId));
            KeyEntity e = new KeyEntity();
            e.setAccount(acc);
            e.setPublicKeyPem(null);
            e.setPrivateKeyBlob(EncryptedBlobCodec.encodeToBytes(encryptedPrivateKeyBlob));
            e.setKeyIv(encryptedPrivateKeyBlob.iv());
            e.setKeySalt(null);
            e.setCreatedAt(OffsetDateTime.now());
            keyRepository.save(e);
        } catch (CoreException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new CoreException("storePrivateKey failed", ex);
        }
    }

    @Override
    public Optional<KeyDTO> findKeyById(Long keyId) throws CoreException {
        try {
            return keyRepository.findById(keyId).map(this::toDto);
        } catch (Exception e) {
            throw new CoreException("findKeyById failed", e);
        }
    }

    @Override
    public Optional<KeyDTO> findPublicKeyByEmail(String email) throws CoreException {
        try {
            Optional<AccountEntity> acc = accountRepository.findByEmail(email);
            if (acc.isEmpty()) return Optional.empty();
            Optional<KeyEntity> k = keyRepository.findPrimaryByAccountId(acc.get().getId());
            return k.map(this::toDto);
        } catch (Exception e) {
            throw new CoreException("findPublicKeyByEmail failed", e);
        }
    }

    @Override
    public String getPublicKeyPem(Long keyId) throws CoreException {
        try {
            KeyEntity e = keyRepository.findById(keyId)
                .orElseThrow(() -> new CoreException("Key not found: " + keyId));
            return e.getPublicKeyPem();
        } catch (CoreException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new CoreException("getPublicKeyPem failed", ex);
        }
    }

    @Override
    public byte[] decryptPrivateKey(Long keyId, char[] masterPassword) throws CoreException {
        byte[] kek = null;
        try {
            KeyEntity e = keyRepository.findById(keyId)
                .orElseThrow(() -> new CoreException("Key not found: " + keyId));
            if (e.getPrivateKeyBlob() == null) throw new CoreException("Private key not available");
            EncryptedBlob blob = EncryptedBlobCodec.decodeFromBytes(e.getPrivateKeyBlob());
            byte[] salt = e.getKeySalt();
            kek = keyMgmt.deriveKEK(masterPassword, salt, KDF_ITERATIONS);
            byte[] raw = keyMgmt.decryptPrivateKey(blob, kek);
            return raw; // caller MUST wipe
        } catch (CoreException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new CoreException("decryptPrivateKey failed", ex);
        } finally {
            if (kek != null) ZeroUtils.wipe(kek);
        }
    }

    @Override
    public void importPublicKey(Long accountId, String pem) throws CoreException {
        try {
            AccountEntity acc = accountRepository.findById(accountId)
                .orElseThrow(() -> new CoreException("Account not found: " + accountId));
            KeyEntity e = new KeyEntity();
            e.setAccount(acc);
            e.setPublicKeyPem(pem);
            e.setCreatedAt(OffsetDateTime.now());
            keyRepository.save(e);
        } catch (CoreException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new CoreException("importPublicKey failed", ex);
        }
    }

    // helper
    private KeyDTO toDto(KeyEntity e) {
        if (e == null) return null;
        Long accountId = e.getAccount() == null ? null : e.getAccount().getId();
        String keyId = e.getId() == null ? null : "key-" + e.getId();
        boolean privStored = e.getPrivateKeyBlob() != null && e.getPrivateKeyBlob().length > 0;
        Instant created = e.getCreatedAt() == null ? null : e.getCreatedAt().toInstant();
        return new KeyDTO(e.getId(), accountId, keyId, e.getPublicKeyPem(), privStored, created);
    }

    private static byte[] randomBytes(int len) {
        byte[] b = new byte[len];
        new java.security.SecureRandom().nextBytes(b);
        return b;
    }
}
