package ru.study.service.dto;

import lombok.Builder;
import lombok.Value;

/**
 * Account creation request. Password is provided encrypted (string) — service will decrypt via KeyManagement.
 */
@Value
@Builder
public class CreateAccountRequest {
    String email;
    String displayName;
    String username;
    /**
    * Зашифрованный пароль
    */
    String password;
    String imapHost;
    int imapPort;
    boolean imapSsl;
    String smtpHost;
    int smtpPort;
    boolean smtpSsl;
    boolean useTls;
}