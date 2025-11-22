package ru.study.crypto.api;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;

public interface AsymmetricCipher {
    KeyPair generateKeyPair(int bits);
    byte[] encrypt(PublicKey pub, byte[] data);
    byte[] decrypt(PrivateKey priv, byte[] cipher);
}
