package ru.study.crypto.api;

import java.security.PrivateKey;
import java.security.PublicKey;

public interface Signer {
    byte[] sign(PrivateKey privateKey, byte[] data);
    boolean verify(PublicKey publicKey, byte[] data, byte[] signature);
}
