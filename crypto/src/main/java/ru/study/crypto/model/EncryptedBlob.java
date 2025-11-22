package ru.study.crypto.model;

public final class EncryptedBlob {
    private final byte[] iv;
    private final byte[] cipherText;
    private final String algorithm; // e.g. "AES-GCM" or "DES-ECB"

    public EncryptedBlob(byte[] iv, byte[] cipherText, String algorithm) {
        this.iv = iv;
        this.cipherText = cipherText;
        this.algorithm = algorithm;
    }

    public byte[] iv() { return iv; }
    public byte[] cipherText() { return cipherText; }
    public String algorithm() { return algorithm; }
}
