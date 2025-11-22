package ru.study.crypto.impl.md;

import ru.study.crypto.api.Digest;
import ru.study.core.exception.CryptoException;

import java.security.MessageDigest;

public class MdDigest implements Digest {

    @Override
    public byte[] digest(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return md.digest(data);
        } catch (Exception e) {
            throw new CryptoException("MD5 digest failed", e);
        }
    }
}
