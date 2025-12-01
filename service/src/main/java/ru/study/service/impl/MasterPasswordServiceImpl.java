package ru.study.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.study.core.exception.CoreException;
import ru.study.persistence.entity.KeyEntity;
import ru.study.persistence.entity.MasterPasswordEntity;
import ru.study.persistence.repository.api.KeyRepository;
import ru.study.persistence.repository.api.MasterPasswordRepository;
import ru.study.crypto.api.KeyManagement;
import ru.study.crypto.util.EncryptedBlobCodec;
import ru.study.crypto.util.ZeroUtils;
import ru.study.service.api.MasterPasswordService;
import ru.study.service.api.NotificationService;
import ru.study.core.event.MasterPasswordChangedEvent;
import ru.study.core.event.bus.EventBus;

import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public class MasterPasswordServiceImpl implements MasterPasswordService {

    private static final Logger log = LoggerFactory.getLogger(MasterPasswordServiceImpl.class);

    private final KeyRepository keyRepository;
    private final MasterPasswordRepository masterPasswordRepository;
    private final KeyManagement keyManagement;
    private final NotificationService notificationService;
    private final EventBus eventBus;

    private static final int KDF_ITERATIONS = 100_000;
    private static final int SALT_LEN = 16;

    private volatile char[] currentMasterPassword = null;

    public MasterPasswordServiceImpl(
            KeyRepository keyRepository,
            MasterPasswordRepository masterPasswordRepository,
            KeyManagement keyManagement,
            NotificationService notificationService,
            EventBus eventBus
    ) {
        this.keyRepository = keyRepository;
        this.masterPasswordRepository = masterPasswordRepository;
        this.keyManagement = keyManagement;
        this.notificationService = notificationService;
        this.eventBus = eventBus;
    }

    @Override
    public synchronized void initializeMasterPassword(char[] password) throws CoreException {
        try {
            if (masterPasswordRepository.findAny().isPresent()) {
                throw new CoreException("Master password already initialized");
            }
            byte[] salt = CryptoRandom.randomBytes(SALT_LEN);
            byte[] derived = null;
            try {
                derived = keyManagement.deriveKEK(password, salt, KDF_ITERATIONS);
                byte[] verifier = md5(derived);

                MasterPasswordEntity mpe = new MasterPasswordEntity();
                mpe.setVerifierSalt(Arrays.copyOf(salt, salt.length));
                mpe.setVerifierHash(verifier);
                mpe.setCreatedAt(OffsetDateTime.now());
                masterPasswordRepository.save(mpe);

                setCurrentMasterPassword(Arrays.copyOf(password, password.length));
                notificationService.notifySuccess("Master password initialized");
            } finally {
                if (derived != null) ZeroUtils.wipe(derived);
                ZeroUtils.wipe(salt);
            }
        } catch (CoreException e) { throw e; }
        catch (Exception e) { 
            log.error("initializeMasterPassword failed", e);
            throw new CoreException("initializeMasterPassword failed", e); 
        }
    }

    @Override
    public boolean verifyMasterPassword(char[] password) throws CoreException {
        try {
            Optional<MasterPasswordEntity> opt = masterPasswordRepository.findAny();
            if (opt.isEmpty()) return false;
            MasterPasswordEntity mpe = opt.get();
            byte[] verifierSalt = mpe.getVerifierSalt();
            byte[] expected = mpe.getVerifierHash();
            if (verifierSalt == null || expected == null) return false;

            byte[] derived = keyManagement.deriveKEK(password, verifierSalt, KDF_ITERATIONS);
            try {
                byte[] hash = md5(derived);
                boolean ok = constantTimeEquals(hash, expected);
                if (ok) setCurrentMasterPassword(Arrays.copyOf(password, password.length));
                return ok;
            } finally { ZeroUtils.wipe(derived); }
        } catch (CoreException e) { throw e; }
        catch (Exception e) { 
            log.error("verifyMasterPassword failed", e);
            throw new CoreException("verifyMasterPassword failed", e); 
        }
    }

    @Override
    public void changeMasterPassword(char[] oldPass, char[] newPass) throws CoreException {
        if (!verifyMasterPassword(oldPass)) throw new CoreException("Old master password is incorrect");

        byte[] oldKek = null;
        byte[] newVerifierSalt = CryptoRandom.randomBytes(SALT_LEN);

        try {
            MasterPasswordEntity mpe = masterPasswordRepository.findAny()
                    .orElseThrow(() -> new CoreException("Master password not initialized"));

            byte[] oldVerifierSalt = Arrays.copyOf(mpe.getVerifierSalt(), mpe.getVerifierSalt().length);
            oldKek = keyManagement.deriveKEK(oldPass, oldVerifierSalt, KDF_ITERATIONS);

            // Re-encrypt all private keys — generate per-key salt/kek
            List<KeyEntity> keys = keyRepository.findAll();
            for (KeyEntity ke : keys) {
                if (ke.getPrivateKeyBlob() == null) continue;
                var blob = EncryptedBlobCodec.decodeFromBytes(ke.getPrivateKeyBlob());
                byte[] rawPriv = null;
                byte[] perKeySalt = null;
                byte[] perKeyKek = null;
                try {
                    rawPriv = keyManagement.decryptPrivateKey(blob, oldKek);
                    // per-key salt
                    perKeySalt = CryptoRandom.randomBytes(SALT_LEN);
                    perKeyKek = keyManagement.deriveKEK(newPass, perKeySalt, KDF_ITERATIONS);
                    var newBlob = keyManagement.encryptPrivateKey(rawPriv, perKeyKek);
                    ke.setPrivateKeyBlob(EncryptedBlobCodec.encodeToBytes(newBlob));
                    ke.setKeyIv(newBlob.iv());
                    // save a copy of salt array
                    ke.setKeySalt(Arrays.copyOf(perKeySalt, perKeySalt.length));
                    keyRepository.save(ke);
                } finally {
                    if (rawPriv != null) ZeroUtils.wipe(rawPriv);
                    if (perKeyKek != null) ZeroUtils.wipe(perKeyKek);
                    if (perKeySalt != null) ZeroUtils.wipe(perKeySalt);
                }
            }

            // update verifier (single row) — store copy of salt
            byte[] newVerifierKek = keyManagement.deriveKEK(newPass, newVerifierSalt, KDF_ITERATIONS);
            try {
                byte[] verifierHash = md5(newVerifierKek);
                mpe.setVerifierSalt(Arrays.copyOf(newVerifierSalt, newVerifierSalt.length));
                mpe.setVerifierHash(verifierHash);
                mpe.setCreatedAt(OffsetDateTime.now());
                masterPasswordRepository.save(mpe);
            } finally {
                ZeroUtils.wipe(newVerifierKek);
            }

            setCurrentMasterPassword(Arrays.copyOf(newPass, newPass.length));
            notificationService.notifySuccess("Master password changed and private keys re-encrypted.");
            eventBus.publish(new MasterPasswordChangedEvent());
        } catch (CoreException e) { throw e; }
        catch (Exception e) {
            log.error("changeMasterPassword failed", e);
            throw new CoreException("changeMasterPassword failed", e);
        } finally {
            if (oldKek != null) ZeroUtils.wipe(oldKek);
            // wipe the local newVerifierSalt copy
            ZeroUtils.wipe(newVerifierSalt);
        }
    }

    @Override
    public Optional<char[]> getCurrentMasterPassword() {
        char[] cp = currentMasterPassword;
        return cp == null ? Optional.empty() : Optional.of(Arrays.copyOf(cp, cp.length));
    }

    private synchronized void setCurrentMasterPassword(char[] arr) {
        if (this.currentMasterPassword != null) ZeroUtils.wipe(this.currentMasterPassword);
        this.currentMasterPassword = arr;
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) return false;
        int r = 0;
        for (int i = 0; i < a.length; i++) r |= a[i] ^ b[i];
        return r == 0;
    }

    private static byte[] md5(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        return md.digest(data);
    }

    private static final class CryptoRandom {
        static byte[] randomBytes(int len) {
            byte[] b = new byte[len];
            new java.security.SecureRandom().nextBytes(b);
            return b;
        }
    }
}