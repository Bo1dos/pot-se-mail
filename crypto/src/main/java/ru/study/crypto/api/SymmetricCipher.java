package ru.study.crypto.api;

import ru.study.crypto.model.EncryptedBlob;

public interface SymmetricCipher {
    /**
     * Генерирует случайный симметричный ключ (raw bytes).
     */
    byte[] generateKey();

    /**
     * Шифрует plain и возвращает EncryptedBlob (iv + ciphertext + algo).
     */
    EncryptedBlob encrypt(byte[] key, byte[] plain);

    /**
     * Дешифрует EncryptedBlob с заданным key.
     */
    byte[] decrypt(byte[] key, EncryptedBlob blob);
}
