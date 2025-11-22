package ru.study.crypto.util;

import java.security.SecureRandom;
import java.util.Base64;

public final class CryptoUtils {
    private static final SecureRandom SR = new SecureRandom();

    private CryptoUtils() {}

    public static byte[] randomBytes(int size) {
        byte[] b = new byte[size];
        SR.nextBytes(b);
        return b;
    }

    public static String b64(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }

    public static byte[] b64dec(String s) {
        return Base64.getDecoder().decode(s);
    }
}
