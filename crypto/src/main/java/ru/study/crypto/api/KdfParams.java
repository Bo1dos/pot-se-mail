package ru.study.crypto.api;

public final class KdfParams {
    private final int iterations;
    private final int keyLength; // bits

    public KdfParams(int iterations, int keyLength) { this.iterations = iterations; this.keyLength = keyLength; }
    public int iterations() { return iterations; }
    public int keyLength() { return keyLength; }
}
