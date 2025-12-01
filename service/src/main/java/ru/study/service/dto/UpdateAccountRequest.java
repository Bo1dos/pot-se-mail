package ru.study.service.dto;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class UpdateAccountRequest {
    String displayName;
    String username;
    /**
     * Зашифрованный пароль
     */
    String password; // optional: if null, password not changed
    String imapHost;
    Integer imapPort;
    Boolean imapSsl;
    String smtpHost;
    Integer smtpPort;
    Boolean smtpSsl;
    Boolean useTls;
}
