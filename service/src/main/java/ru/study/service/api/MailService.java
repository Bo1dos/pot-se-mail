package ru.study.service.api;

import ru.study.core.dto.MessageDetailDTO;
import ru.study.core.dto.MessageSummaryDTO;
import ru.study.core.exception.CoreException;
import ru.study.service.dto.SendMessageDTO;
import ru.study.service.dto.SendResultDTO;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface MailService {
    /**
     * Send (blocking) — for MVP.
     */
    SendResultDTO send(SendMessageDTO dto, Long accountId, boolean encrypt, boolean sign) throws CoreException;

    /**
     * Async send (optional).
     */
    CompletableFuture<SendResultDTO> sendAsync(SendMessageDTO dto, Long accountId, boolean encrypt, boolean sign);

    /**
     * Fetch new messages and persist them for account.
     */
    void receiveNew(Long accountId) throws CoreException;

    MessageDetailDTO getMessage(Long accountId, Long messageId) throws CoreException;

    void deleteMessage(Long accountId, Long messageId) throws CoreException;

    List<MessageSummaryDTO> listMessages(Long accountId, String folder, int page, int size) throws CoreException;
}
