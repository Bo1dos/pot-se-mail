package ru.study.crypto.impl.keymgmt;

import ru.study.crypto.api.KeyManagement;
import ru.study.crypto.model.EncryptedBlob;
import ru.study.core.exception.CryptoException;
import ru.study.crypto.util.CryptoUtils;
import ru.study.crypto.util.ZeroUtils;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.spec.KeySpec;

public class KeyManagementImpl implements KeyManagement {

    private static final String KDF_ALGO = "PBKDF2WithHmacSHA256";
    private static final String AES_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;

    @Override
    public byte[] deriveKEK(char[] masterPassword, byte[] salt, int iterations) {
        PBEKeySpec pbe = null;
        try {
            SecretKeyFactory skf = SecretKeyFactory.getInstance(KDF_ALGO);
            pbe = new PBEKeySpec(masterPassword, salt, iterations, 256);
            SecretKey key = skf.generateSecret((KeySpec) pbe);
            byte[] kek = key.getEncoded();
            // пытаемся очистить PBEKeySpec-парольную копию
            pbe.clearPassword();
            return kek; // caller обязан вызвать ZeroUtils.wipe(kek) после использования
        } catch (Exception e) {
            if (pbe != null) pbe.clearPassword();
            throw new CryptoException("KEK derivation failed", e);
        }
    }

    @Override
    public EncryptedBlob encryptPrivateKey(byte[] privateKeyEncoded, byte[] kek) {
        try {
            byte[] iv = CryptoUtils.randomBytes(12); // GCM nonce
            SecretKeySpec sk = new SecretKeySpec(kek, "AES");
            Cipher cipher = Cipher.getInstance(AES_TRANSFORMATION);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_BITS, iv);
            cipher.init(Cipher.ENCRYPT_MODE, sk, gcmSpec);
            byte[] ct = cipher.doFinal(privateKeyEncoded);
            return new EncryptedBlob(iv, ct, AES_TRANSFORMATION);
        } catch (Exception e) {
            throw new CryptoException("encryptPrivateKey failed", e);
        }

    }

    @Override
    public byte[] decryptPrivateKey(EncryptedBlob blob, byte[] kek) {
        try {
            SecretKeySpec sk = new SecretKeySpec(kek, "AES");
            Cipher cipher = Cipher.getInstance(AES_TRANSFORMATION);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_BITS, blob.iv());
            cipher.init(Cipher.DECRYPT_MODE, sk, gcmSpec);
            byte[] plain = cipher.doFinal(blob.cipherText());
            return plain; // caller обязан ZeroUtils.wipe(plain)\
        } catch (Exception e) {
            throw new CryptoException("decryptPrivateKey failed", e);
        }
    }

    @Override
    public EncryptedBlob encryptCredential(String credential, byte[] kek) {
        byte[] tmp = null;
        try {
            tmp = credential.getBytes(StandardCharsets.UTF_8);
            EncryptedBlob blob = encryptPrivateKey(tmp, kek);
            return blob;
        } finally {
            // wipe temporary plaintext bytes
            ZeroUtils.wipe(tmp);
        }
    }

    @Override
    public String decryptCredential(EncryptedBlob blob, byte[] kek) {
        byte[] plain = null;
        try {
            plain = decryptPrivateKey(blob, kek);
            return new String(plain, StandardCharsets.UTF_8);
        } finally {
            ZeroUtils.wipe(plain);
        }
    }
}
