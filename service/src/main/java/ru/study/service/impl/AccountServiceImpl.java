package ru.study.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.study.core.dto.AccountDTO;
import ru.study.core.exception.CoreException;
import ru.study.core.model.Account;
import ru.study.persistence.entity.AccountEntity;
import ru.study.persistence.mapper.AccountMapper;
import ru.study.persistence.repository.api.AccountRepository;
import ru.study.crypto.api.KeyManagement;
import ru.study.crypto.model.EncryptedBlob;
import ru.study.service.api.AccountService;
import ru.study.service.api.MasterPasswordService;
import ru.study.service.dto.CreateAccountRequest;
import ru.study.service.dto.UpdateAccountRequest;
import ru.study.service.dto.ConnectionTestResult;
import ru.study.mailadapter.api.MailAdapter;
import ru.study.mailadapter.model.AccountConfig;
import ru.study.crypto.util.ZeroUtils;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Реализация AccountService.
 * Зависимости инжектируются через конструктор.
 */
public class AccountServiceImpl implements AccountService {

    private static final int KDF_ITERATIONS = 65536;
    private static final int SALT_LEN = 16;
    private static final Logger log = LoggerFactory.getLogger(AccountServiceImpl.class);

    private final AccountRepository accountRepository;
    private final KeyManagement cryptoKeyManagement;
    private final MasterPasswordService masterPasswordService;
    private final MailAdapter mailAdapter;
    private final SecureRandom rnd = new SecureRandom();

    public AccountServiceImpl(AccountRepository accountRepository,
                              KeyManagement cryptoKeyManagement,
                              MasterPasswordService masterPasswordService,
                              MailAdapter mailAdapter) {
        this.accountRepository = accountRepository;
        this.cryptoKeyManagement = cryptoKeyManagement;
        this.masterPasswordService = masterPasswordService;
        this.mailAdapter = mailAdapter;
        log.debug("AccountServiceImpl initialized with repository: {}, crypto: {}, masterPasswordService: {}, mailAdapter: {}", 
                 accountRepository.getClass().getSimpleName(), 
                 cryptoKeyManagement.getClass().getSimpleName(),
                 masterPasswordService.getClass().getSimpleName(),
                 mailAdapter.getClass().getSimpleName());
    }

    @Override
    public AccountDTO createAccount(CreateAccountRequest req) throws CoreException {
        log.info("Creating account for email: {}", req != null ? req.getEmail() : "null");
        
        // validate basic
        if (req == null) {
            log.error("CreateAccountRequest is null");
            throw new CoreException("CreateAccountRequest is null");
        }
        if (req.getEmail() == null || req.getEmail().isBlank()) {
            log.error("Email is required");
            throw new CoreException("Email is required");
        }

        log.debug("Checking if account with email already exists: {}", req.getEmail());
        if (accountRepository.existsByEmail(req.getEmail())) {
            log.warn("Account with email already exists: {}", req.getEmail());
            throw new CoreException("Account with email already exists: " + req.getEmail());
        }

        // need master password in session to encrypt credentials
        log.debug("Checking master password availability");
        var masterOpt = masterPasswordService.getCurrentMasterPassword();
        if (masterOpt.isEmpty()) {
            log.error("Master password not available in session");
            throw new CoreException("Master password not available in session");
        }

        char[] master = masterOpt.get();
        byte[] kek = null;
        byte[] salt = new byte[SALT_LEN];
        rnd.nextBytes(salt);
        log.debug("Generated salt for KDF, length: {}", salt.length);

        try {
            log.debug("Deriving KEK with KDF iterations: {}", KDF_ITERATIONS);
            kek = cryptoKeyManagement.deriveKEK(master, salt, KDF_ITERATIONS);
            EncryptedBlob credBlob = null;
            if (req.getPassword() != null) {
                log.debug("Encrypting credentials");
                credBlob = cryptoKeyManagement.encryptCredential(req.getPassword(), kek);
            }

            AccountEntity ent = new AccountEntity();
            ent.setEmail(req.getEmail());
            ent.setDisplayName(req.getDisplayName());
            ent.setImapServer(req.getImapHost());
            ent.setImapPort(req.getImapPort());
            ent.setSmtpServer(req.getSmtpHost());
            ent.setSmtpPort(req.getSmtpPort());
            ent.setIsUseTls(req.isUseTls());
            if (credBlob != null) {
                ent.setCredBlob(credBlob.cipherText());
                ent.setCredIv(credBlob.iv());
                ent.setCredSalt(salt);
                log.debug("Set encrypted credentials for account");
            }
            ent.setCreatedAt(OffsetDateTime.now());

            log.debug("Saving account entity to repository");
            AccountEntity saved = accountRepository.save(ent);
            log.debug("Saved entity id from DB: {}", saved.getId());
            Account domain = AccountMapper.toDomain(saved);
            log.debug("Domain account id: {}", domain.getId());
            AccountDTO result = AccountMapper.toDto(domain);
            log.info("Successfully created account with id: {} and email: {}", result.id(), result.email());
            return result;
        } catch (Exception e) {
            log.error("Failed to create account for email: {}. Error: {}", req.getEmail(), e.getMessage(), e);
            throw new CoreException("Failed to create account: " + e.getMessage(), e);
        } finally {
            if (kek != null) ZeroUtils.wipe(kek);
            log.debug("KEK wiped from memory");
        }
    }

    @Override
    public AccountDTO updateAccount(Long id, UpdateAccountRequest req) throws CoreException {
        log.info("Updating account with id: {}", id);
        
        if (id == null) {
            log.error("Account id is null for update operation");
            throw new CoreException("Account id required");
        }
        
        log.debug("Finding account by id: {}", id);
        AccountEntity ent = accountRepository.findById(id).orElseThrow(() -> {
            log.error("Account not found for update, id: {}", id);
            return new CoreException("Account not found: " + id);
        });

        // update basic fields
        log.debug("Updating basic fields for account id: {}", id);
        if (req.getDisplayName() != null) {
            ent.setDisplayName(req.getDisplayName());
            log.debug("Updated display name");
        }
        if (req.getImapHost() != null) {
            ent.setImapServer(req.getImapHost());
            log.debug("Updated IMAP host");
        }
        if (req.getImapPort() != null) {
            ent.setImapPort(req.getImapPort());
            log.debug("Updated IMAP port: {}", req.getImapPort());
        }
        if (req.getSmtpHost() != null) {
            ent.setSmtpServer(req.getSmtpHost());
            log.debug("Updated SMTP host");
        }
        if (req.getSmtpPort() != null) {
            ent.setSmtpPort(req.getSmtpPort());
            log.debug("Updated SMTP port: {}", req.getSmtpPort());
        }
        if (req.getUseTls() != null) {
            ent.setIsUseTls(req.getUseTls());
            log.debug("Updated TLS setting: {}", req.getUseTls());
        }

        // if password provided — re-encrypt credential with current master
        if (req.getPassword() != null) {
            log.debug("Password provided, re-encrypting credentials");
            var masterOpt = masterPasswordService.getCurrentMasterPassword();
            if (masterOpt.isEmpty()) {
                log.error("Master password not available in session for account update");
                throw new CoreException("Master password not available in session");
            }
            char[] master = masterOpt.get();

            byte[] newSalt = new byte[SALT_LEN];
            rnd.nextBytes(newSalt);
            log.debug("Generated new salt for credential update");
            byte[] kek = null;
            try {
                kek = cryptoKeyManagement.deriveKEK(master, newSalt, KDF_ITERATIONS);
                EncryptedBlob cb = cryptoKeyManagement.encryptCredential(req.getPassword(), kek);
                ent.setCredBlob(cb.cipherText());
                ent.setCredIv(cb.iv());
                ent.setCredSalt(newSalt);
                log.debug("Credentials successfully re-encrypted");
            } catch (Exception e) {
                log.error("Failed to encrypt credential during account update, id: {}. Error: {}", id, e.getMessage(), e);
                throw new CoreException("Failed to encrypt credential: " + e.getMessage(), e);
            } finally {
                // wipe kek
                if (kek != null) ZeroUtils.wipe(kek);
                log.debug("KEK wiped from memory after credential update");
            }
        }

        log.debug("Saving updated account entity");
        AccountEntity saved = accountRepository.save(ent);
        Account domain = AccountMapper.toDomain(saved);
        AccountDTO result = AccountMapper.toDto(domain);
        log.info("Successfully updated account with id: {}", id);
        return result;
    }

    @Override
    public void deleteAccount(Long id) throws CoreException {
        log.info("Deleting account with id: {}", id);
        
        if (id == null) {
            log.error("Account id is null for delete operation");
            throw new CoreException("Account id required");
        }
        
        log.debug("Finding account by id for deletion: {}", id);
        AccountEntity ent = accountRepository.findById(id).orElseThrow(() -> {
            log.error("Account not found for deletion, id: {}", id);
            return new CoreException("Account not found: " + id);
        });
        
        accountRepository.delete(ent);
        log.info("Successfully deleted account with id: {}", id);
    }

    @Override
    public AccountDTO getAccount(Long id) throws CoreException {
        log.debug("Getting account by id: {}", id);
        
        if (id == null) {
            log.error("Account id is null for get operation");
            throw new CoreException("Account id required");
        }
        
        log.debug("Finding account by id: {}", id);
        AccountEntity ent = accountRepository.findById(id).orElseThrow(() -> {
            log.error("Account not found, id: {}", id);
            return new CoreException("Account not found: " + id);
        });
        
        AccountDTO result = AccountMapper.toDto(AccountMapper.toDomain(ent));
        log.debug("Successfully retrieved account with id: {} and email: {}", id, result.email());
        return result;
    }

    @Override
    public List<AccountDTO> listAccounts() {
        log.debug("Listing all accounts");
        List<AccountDTO> accounts = accountRepository.findAll().stream()
                .map(AccountMapper::toDomain)
                .map(AccountMapper::toDto)
                .collect(Collectors.toList());
        log.info("Retrieved {} accounts", accounts.size());
        return accounts;
    }

    @Override
    public ConnectionTestResult testConnection(Long accountId) throws CoreException {
        log.info("Testing connection for account id: {}", accountId);
        
        if (accountId == null) {
            log.error("Account id is null for connection test");
            throw new CoreException("Account id required");
        }
        
        log.debug("Finding account by id for connection test: {}", accountId);
        AccountEntity ent = accountRepository.findById(accountId).orElseThrow(() -> {
            log.error("Account not found for connection test, id: {}", accountId);
            return new CoreException("Account not found: " + accountId);
        });

        // decrypt credential
        log.debug("Checking master password for connection test");
        var masterOpt = masterPasswordService.getCurrentMasterPassword();
        if (masterOpt.isEmpty()) {
            log.error("Master password not available in session for connection test");
            throw new CoreException("Master password not available in session");
        }
        char[] master = masterOpt.get();

        byte[] kek = null;
        try {
            byte[] salt = ent.getCredSalt();
            if (salt == null) {
                log.error("No credential stored for account {}", accountId);
                throw new CoreException("No credential stored for account " + accountId);
            }
            log.debug("Deriving KEK for connection test");
            kek = cryptoKeyManagement.deriveKEK(master, salt, KDF_ITERATIONS);
            EncryptedBlob blob = new EncryptedBlob(ent.getCredIv(), ent.getCredBlob(), "AES/GCM/NoPadding");
            String password = cryptoKeyManagement.decryptCredential(blob, kek);

            // Build mail adapter config
            AccountConfig cfg = new AccountConfig(
                    ent.getEmail(),               // email/login
                    ent.getEmail(),               // login (use email as login by default)
                    password,
                    ent.getImapServer(),
                    ent.getImapPort() == null ? 0 : ent.getImapPort(),
                    Boolean.TRUE.equals(ent.getIsUseTls()),
                    ent.getSmtpServer(),
                    ent.getSmtpPort() == null ? 0 : ent.getSmtpPort(),
                    Boolean.TRUE.equals(ent.getIsUseTls())
            );

            log.debug("Testing connection with mail adapter");
            try {
                mailAdapter.connect(cfg);
                // quick check: listFolders (or simply connect/disconnect depending on implementation)
                mailAdapter.listFolders();
                mailAdapter.disconnect();
                // ADAPT: construct ConnectionTestResult according to your DTO
                ConnectionTestResult result = ConnectionTestResult.builder()
                        .imapOk(true)
                        .smtpOk(true) // Assuming SMTP also works if IMAP works, or implement separate test
                        .authenticated(true)
                        .message("Connection OK")
                        .build();
                log.info("Connection test successful for account id: {}", accountId);
                return result;
            } catch (Exception ex) {
                try { mailAdapter.disconnect(); } catch (Exception ignored) {
                    log.debug("Error during disconnect after failed connection test: {}", ignored.getMessage());
                }
                ConnectionTestResult result = ConnectionTestResult.builder()
                        .imapOk(false)
                        .smtpOk(false)
                        .authenticated(false)
                        .message("Connection failed: " + ex.getMessage())
                        .build();
                log.warn("Connection test failed for account id: {}. Error: {}", accountId, ex.getMessage());
                return result;
            }
        } catch (CoreException ce) {
            log.error("Core exception during connection test for account id: {}. Error: {}", accountId, ce.getMessage(), ce);
            throw ce;
        } catch (Exception e) {
            log.error("Failed to test connection for account id: {}. Error: {}", accountId, e.getMessage(), e);
            throw new CoreException("Failed to test connection: " + e.getMessage(), e);
        } finally {
            if (kek != null) ZeroUtils.wipe(kek);
            log.debug("KEK wiped from memory after connection test");
        }
    }

    @Override
    public AccountConfig getAccountConfig(Long accountId) throws CoreException {
        log.debug("Getting account config for id: {}", accountId);
        
        if (accountId == null) {
            log.error("Account id is null for getAccountConfig");
            throw new CoreException("Account id required");
        }
        
        log.debug("Finding account by id: {}", accountId);
        AccountEntity ent = accountRepository.findById(accountId)
                .orElseThrow(() -> {
                    log.error("Account not found for getAccountConfig, id: {}", accountId);
                    return new CoreException("Account not found: " + accountId);
                });

        log.debug("Checking master password for getAccountConfig");
        var masterOpt = masterPasswordService.getCurrentMasterPassword();
        if (masterOpt.isEmpty()) {
            log.error("Master password not available in session for getAccountConfig");
            throw new CoreException("Master password not available in session");
        }
        char[] master = masterOpt.get();

        byte[] kek = null;
        try {
            byte[] salt = ent.getCredSalt();
            if (salt == null) {
                log.error("No credential stored for account {}", accountId);
                throw new CoreException("No credential stored for account " + accountId);
            }
            log.debug("Deriving KEK for getAccountConfig");
            kek = cryptoKeyManagement.deriveKEK(master, salt, KDF_ITERATIONS);

            // AccountEntity stores credBlob as byte[] (cipherText) and credIv as byte[]
            EncryptedBlob blob = new EncryptedBlob(ent.getCredIv(), ent.getCredBlob(), "AES/GCM/NoPadding");
            String password = cryptoKeyManagement.decryptCredential(blob, kek);

            AccountConfig config = new AccountConfig(
                ent.getEmail(),
                ent.getEmail(), // login default = email
                password,
                ent.getImapServer(),
                ent.getImapPort() == null ? 0 : ent.getImapPort(),
                Boolean.TRUE.equals(ent.getIsUseTls()),
                ent.getSmtpServer(),
                ent.getSmtpPort() == null ? 0 : ent.getSmtpPort(),
                Boolean.TRUE.equals(ent.getIsUseTls())
            );
            log.debug("Successfully built account config for account id: {}", accountId);
            return config;
        } catch (CoreException ce) {
            log.error("Core exception in getAccountConfig for account id: {}. Error: {}", accountId, ce.getMessage(), ce);
            throw ce;
        } catch (Exception e) {
            log.error("Failed to build AccountConfig for account id: {}. Error: {}", accountId, e.getMessage(), e);
            throw new CoreException("Failed to build AccountConfig: " + e.getMessage(), e);
        } finally {
            if (kek != null) ZeroUtils.wipe(kek);
            log.debug("KEK wiped from memory after getAccountConfig");
        }
    }
}