package ru.study.service.dto;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class ConnectionTestResult {
    boolean imapOk;
    boolean smtpOk;
    boolean authenticated;
    String message; // details or error
}
