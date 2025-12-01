package ru.study.service.api;

public interface NotificationService {
    void notifyInfo(String message);
    void notifyError(String message, Throwable t);
    void notifySuccess(String message);
}
