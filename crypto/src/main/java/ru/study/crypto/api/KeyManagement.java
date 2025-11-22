package ru.study.crypto.api;

import ru.study.crypto.model.EncryptedBlob;

public interface KeyManagement {
    /**
     * Дериватит KEK из пароля пользователя.
     */
    byte[] deriveKEK(char[] masterPassword, byte[] salt, int iterations);

    /**
     * Шифрует приватный ключ (к примеру, RSA PKCS8-encoded) с использованием KEK и возвращает EncryptedBlob.
     */
    EncryptedBlob encryptPrivateKey(byte[] privateKeyEncoded, byte[] kek);

    /**
     * Дешифрует blob и возвращает raw приватный ключ bytes (PKCS8).
     */
    byte[] decryptPrivateKey(EncryptedBlob blob, byte[] kek);

    /**
     * Шифрует credential (строка) и возвращает blob.
     */
    EncryptedBlob encryptCredential(String credential, byte[] kek);

    String decryptCredential(EncryptedBlob blob, byte[] kek);
}
