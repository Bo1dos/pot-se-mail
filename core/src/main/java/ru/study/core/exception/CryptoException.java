package ru.study.core.exception;

public class CryptoException extends CoreException {
    public CryptoException(String message) { super(message); }
    public CryptoException(String message, Throwable cause) { super(message, cause); }
}
