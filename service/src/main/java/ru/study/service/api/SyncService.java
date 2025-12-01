package ru.study.service.api;

import ru.study.core.exception.CoreException;
import ru.study.service.dto.SyncResult;

public interface SyncService {
    void syncAccount(Long accountId) throws CoreException;
    void startAutoSync();
    void stopAutoSync();
    SyncResult syncFolder(Long accountId, String folderName) throws CoreException;
}
