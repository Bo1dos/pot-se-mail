package ru.study.core.exception;

public class ValidationException extends CoreException {
    public ValidationException(String message) { super(message); }
    public ValidationException(String message, Throwable cause) { super(message, cause); }
}
