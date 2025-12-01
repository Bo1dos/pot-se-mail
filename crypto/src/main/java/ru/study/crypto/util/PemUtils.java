package ru.study.crypto.util;

import ru.study.core.exception.CryptoException;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.PrivateKey;
import java.security.spec.*;
import java.util.Base64;

public final class PemUtils {
    private PemUtils() {}

    public static String toPemPublicKey(java.security.PublicKey publicKey) {
        String b64 = Base64.getEncoder().encodeToString(publicKey.getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + chunk(b64) + "-----END PUBLIC KEY-----\n";
    }

    public static String toPemPrivateKey(java.security.PrivateKey privateKey) {
        String b64 = Base64.getEncoder().encodeToString(privateKey.getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + chunk(b64) + "-----END PRIVATE KEY-----\n";
    }

    public static PublicKey publicKeyFromPem(String pem) {
        try {
            String b64 = extractBase64(pem, "PUBLIC KEY");
            byte[] bytes = Base64.getDecoder().decode(b64);
            X509EncodedKeySpec spec = new X509EncodedKeySpec(bytes);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            return kf.generatePublic(spec);
        } catch (Exception e) {
            throw new CryptoException("Failed to parse public key PEM", e);
        }
    }

    public static PrivateKey privateKeyFromPem(String pem) {
        try {
            String b64 = extractBase64(pem, "PRIVATE KEY");
            byte[] bytes = Base64.getDecoder().decode(b64);
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(bytes);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            return kf.generatePrivate(spec);
        } catch (Exception e) {
            throw new CryptoException("Failed to parse private key PEM", e);
        }
    }

    private static String extractBase64(String pem, String label) {
        String head = "-----BEGIN " + label + "-----";
        String tail = "-----END " + label + "-----";
        int i = pem.indexOf(head);
        if (i < 0) throw new CryptoException("PEM missing header: " + head);
        int j = pem.indexOf(tail, i);
        if (j < 0) throw new CryptoException("PEM missing footer: " + tail);
        String inner = pem.substring(i + head.length(), j).replaceAll("\\s", "");
        return inner;
    }

    private static String chunk(String base64) {
        StringBuilder sb = new StringBuilder();
        int idx = 0;
        while (idx < base64.length()) {
            int end = Math.min(idx + 64, base64.length());
            sb.append(base64, idx, end).append("\n");
            idx = end;
        }
        return sb.toString();
    }

    public static PrivateKey privateKeyFromPkcs8(byte[] pkcs8) {
        try {
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(pkcs8);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            return kf.generatePrivate(spec);
        } catch (Exception e) {
            throw new ru.study.core.exception.CryptoException("Failed to parse PKCS8 private key", e);
        }
    }
}
