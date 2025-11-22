package ru.study.crypto.provider;

import ru.study.crypto.api.AsymmetricCipher;
import ru.study.crypto.api.Digest;
import ru.study.crypto.api.KeyManagement;
import ru.study.crypto.api.Signer;
import ru.study.crypto.api.SymmetricCipher;

public interface CryptoProviderFactory {
    SymmetricCipher getSymmetricCipher(String id);   // e.g. "DES-ECB", "AES-GCM"
    AsymmetricCipher getAsymmetricCipher(String id); // e.g. "RSA"
    Digest getDigest(String id);                     // e.g. "MD5"
    Signer getSigner(String id);                     // e.g. "MD5withRSA"
    KeyManagement getKeyManagement();                // single impl (configurable)
}
