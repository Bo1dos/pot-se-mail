package ru.study.service.api;

import ru.study.core.dto.FolderDTO;
import ru.study.core.exception.CoreException;

import java.util.List;

public interface FolderService {
    List<FolderDTO> listFolders(Long accountId) throws CoreException;
    FolderDTO createFolder(Long accountId, String folderName) throws CoreException;
    void deleteFolder(Long accountId, Long folderId) throws CoreException;
    FolderDTO renameFolder(Long accountId, Long folderId, String newName) throws CoreException;
    void setLastSyncUid(Long accountId, Long folderId, String lastSyncUid) throws CoreException;
}
