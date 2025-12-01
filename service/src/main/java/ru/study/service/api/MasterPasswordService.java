package ru.study.service.api;

import java.util.Optional;

import ru.study.core.exception.CoreException;

public interface MasterPasswordService {
    boolean verifyMasterPassword(char[] password) throws CoreException;
    /**
     * Change master password and re-encrypt stored private keys.
     * Operation may be long — implementation must handle progress reporting.
     */
    void changeMasterPassword(char[] oldPass, char[] newPass) throws CoreException;

    Optional<char[]> getCurrentMasterPassword();

    void initializeMasterPassword(char[] password);

}
