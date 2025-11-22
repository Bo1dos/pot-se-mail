package ru.study.crypto.impl.des;

import ru.study.crypto.api.SymmetricCipher;
import ru.study.crypto.model.EncryptedBlob;
import ru.study.core.exception.CryptoException;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

public class DesEcbCipher implements SymmetricCipher {

    private static final String ALGO = "DES";
    private static final String TRANSFORMATION = "DES/ECB/PKCS5Padding";

    @Override
    public byte[] generateKey() {
        try {
            KeyGenerator kg = KeyGenerator.getInstance(ALGO);
            kg.init(56); // DES key size (effective)
            SecretKey key = kg.generateKey();
            return key.getEncoded();
        } catch (Exception e) {
            throw new CryptoException("Failed to generate DES key", e);
        }
    }

    @Override
    public EncryptedBlob encrypt(byte[] key, byte[] plain) {
        try {
            SecretKeySpec sk = new SecretKeySpec(key, ALGO);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, sk);
            byte[] ct = cipher.doFinal(plain);
            // ECB doesn't use IV;
            return new EncryptedBlob(new byte[0], ct, TRANSFORMATION);
        } catch (Exception e) {
            throw new CryptoException("DES encryption failed", e);
        }
    }

    @Override
    public byte[] decrypt(byte[] key, EncryptedBlob blob) {
        try {
            SecretKeySpec sk = new SecretKeySpec(key, ALGO);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, sk);
            return cipher.doFinal(blob.cipherText());
        } catch (Exception e) {
            throw new CryptoException("DES decryption failed", e);
        }
    }
}
