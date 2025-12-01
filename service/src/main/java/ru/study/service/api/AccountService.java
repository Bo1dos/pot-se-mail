package ru.study.service.api;

import ru.study.core.dto.AccountDTO;
import ru.study.core.exception.CoreException;
import ru.study.mailadapter.model.AccountConfig;
import ru.study.service.dto.ConnectionTestResult;
import ru.study.service.dto.CreateAccountRequest;
import ru.study.service.dto.UpdateAccountRequest;

import java.util.List;

public interface AccountService {
    AccountDTO createAccount(CreateAccountRequest req) throws CoreException;
    AccountDTO updateAccount(Long id, UpdateAccountRequest req) throws CoreException;
    void deleteAccount(Long id) throws CoreException;
    AccountDTO getAccount(Long id) throws CoreException;
    List<AccountDTO> listAccounts();
    ConnectionTestResult testConnection(Long accountId) throws CoreException;
     /**
     * Возвращает AccountConfig (включая plaintext password) — для MailAdapter.
     * AccountService сам должен расшифровать cred_blob используя текущий master password.
     */
    AccountConfig getAccountConfig(Long accountId) throws ru.study.core.exception.CoreException;
}
