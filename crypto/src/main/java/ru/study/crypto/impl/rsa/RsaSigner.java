package ru.study.crypto.impl.rsa;

import ru.study.crypto.api.Signer;
import ru.study.core.exception.CryptoException;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;

public class RsaSigner implements Signer {

    // ПО ТЗ: указан MD5 как hash; используем MD5withRSA для совместимости с требованиями.
    private static final String SIGN_ALGO = "MD5withRSA";

    @Override
    public byte[] sign(PrivateKey privateKey, byte[] data) {
        try {
            Signature sig = Signature.getInstance(SIGN_ALGO);
            sig.initSign(privateKey);
            sig.update(data);
            return sig.sign();
        } catch (Exception e) {
            throw new CryptoException("RSA sign failed", e);
        }
    }

    @Override
    public boolean verify(PublicKey publicKey, byte[] data, byte[] signature) {
        try {
            Signature sig = Signature.getInstance(SIGN_ALGO);
            sig.initVerify(publicKey);
            sig.update(data);
            return sig.verify(signature);
        } catch (Exception e) {
            throw new CryptoException("RSA verify failed", e);
        }
    }
}
