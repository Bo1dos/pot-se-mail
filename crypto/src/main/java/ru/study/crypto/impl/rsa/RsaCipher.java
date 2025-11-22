package ru.study.crypto.impl.rsa;

import ru.study.crypto.api.AsymmetricCipher;
import ru.study.core.exception.CryptoException;

import javax.crypto.Cipher;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;

public class RsaCipher implements AsymmetricCipher {

    private static final String ALGO = "RSA";
    // PKCS1 padding — для совместимости с большинством клиентов для шифрования ключей
    private static final String TRANSFORMATION = "RSA/ECB/PKCS1Padding";

    @Override
    public KeyPair generateKeyPair(int bits) {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance(ALGO);
            kpg.initialize(bits);
            return kpg.generateKeyPair();
        } catch (Exception e) {
            throw new CryptoException("RSA keypair generation failed", e);
        }
    }

    @Override
    public byte[] encrypt(PublicKey pub, byte[] data) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, pub);
            return cipher.doFinal(data);
        } catch (Exception e) {
            throw new CryptoException("RSA encrypt failed", e);
        }
    }

    @Override
    public byte[] decrypt(PrivateKey priv, byte[] cipherText) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, priv);
            return cipher.doFinal(cipherText);
        } catch (Exception e) {
            throw new CryptoException("RSA decrypt failed", e);
        }
    }


}
