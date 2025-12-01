package ru.study.crypto.util;

import ru.study.crypto.model.EncryptedBlob;
import ru.study.core.exception.CryptoException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class EncryptedBlobCodec {
    private static final String SEP = "|";

    private EncryptedBlobCodec() {}

    public static String encode(EncryptedBlob blob) {
        if (blob == null) return null;
        try {
            String alg = blob.algorithm();
            String iv = blob.iv() == null ? "" : Base64.getEncoder().encodeToString(blob.iv());
            String ct = blob.cipherText() == null ? "" : Base64.getEncoder().encodeToString(blob.cipherText());
            return alg + SEP + iv + SEP + ct;
        } catch (Exception e) {
            throw new CryptoException("Failed to encode EncryptedBlob", e);
        }
    }

    public static EncryptedBlob decode(String encoded) {
        if (encoded == null) return null;
        try {
            String[] parts = encoded.split("\\" + SEP, 3);
            if (parts.length < 3) throw new IllegalArgumentException("Invalid EncryptedBlob encoding");
            String alg = parts[0];
            byte[] iv = parts[1].isEmpty() ? new byte[0] : Base64.getDecoder().decode(parts[1]);
            byte[] ct = parts[2].isEmpty() ? new byte[0] : Base64.getDecoder().decode(parts[2]);
            return new EncryptedBlob(iv, ct, alg);
        } catch (Exception e) {
            throw new CryptoException("Failed to decode EncryptedBlob", e);
        }
    }

    // --- new helpers for byte[] (BLOB storage) ---
    public static byte[] encodeToBytes(EncryptedBlob blob) {
        String s = encode(blob);
        return s == null ? null : s.getBytes(StandardCharsets.UTF_8);
    }

    public static EncryptedBlob decodeFromBytes(byte[] data) {
        if (data == null) return null;
        return decode(new String(data, StandardCharsets.UTF_8));
    }
}
