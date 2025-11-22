package ru.study.core.exception;

public class CoreException extends RuntimeException {
    public CoreException() { super(); }
    public CoreException(String message) { super(message); }
    public CoreException(String message, Throwable cause) { super(message, cause); }
    public CoreException(Throwable cause) { super(cause); }
}
