package ru.study.service.impl;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;
import ru.study.core.dto.FolderDTO;
import ru.study.core.exception.CoreException;
import ru.study.core.exception.NotFoundException;
import ru.study.core.model.Folder;
import ru.study.persistence.entity.FolderEntity;
import ru.study.persistence.mapper.FolderMapper;
import ru.study.persistence.repository.api.AccountRepository;
import ru.study.persistence.repository.api.FolderRepository;
import ru.study.persistence.repository.api.MessageRepository;
import ru.study.persistence.repository.impl.FolderRepositoryImpl;
import ru.study.persistence.util.EntityManagerFactoryProvider;
import ru.study.service.api.FolderService;

import java.util.List;
import java.util.stream.Collectors;

/**
 * FolderServiceImpl — использует per-method EntityManager для безопасности и простоты.
 */
public class FolderServiceImpl implements FolderService {

    private final AccountRepository accountRepository;
    private final MessageRepository messageRepository; // optional, can be null

    public FolderServiceImpl(AccountRepository accountRepository, MessageRepository messageRepository) {
        this.accountRepository = accountRepository;
        this.messageRepository = messageRepository;
    }

    public FolderServiceImpl(AccountRepository accountRepository) {
        this(accountRepository, null);
    }

    @Override
    public List<FolderDTO> listFolders(Long accountId) throws CoreException {
        EntityManager em = EntityManagerFactoryProvider.createEntityManager();
        try {
            FolderRepository folderRepo = new FolderRepositoryImpl(em);
            return folderRepo.findByAccountId(accountId).stream()
                    .map(FolderMapper::toDomain)
                    .map(FolderMapper::toDto)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            throw new CoreException("Failed to list folders for account: " + accountId, e);
        } finally {
            if (em != null && em.isOpen()) em.close();
        }
    }

    @Override
    public FolderDTO createFolder(Long accountId, String folderName) throws CoreException {
        if (accountId == null) throw new CoreException("accountId required");
        if (folderName == null || folderName.isBlank()) throw new CoreException("folderName required");

        if (!accountRepository.existsById(accountId)) {
            throw new NotFoundException("Account not found: " + accountId);
        }

        EntityManager em = EntityManagerFactoryProvider.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        try {
            FolderRepository folderRepo = new FolderRepositoryImpl(em);

            tx.begin();

            // Build domain folder (lastSyncUid as null)
            Folder folder = new Folder(null, accountId, folderName, folderName, null);
            FolderEntity entity = FolderMapper.toEntity(folder);
            FolderEntity saved = folderRepo.save(entity);

            tx.commit();

            return FolderMapper.toDto(FolderMapper.toDomain(saved));
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw new CoreException("Failed to create folder: " + e.getMessage(), e);
        } finally {
            if (em != null && em.isOpen()) em.close();
        }
    }

    @Override
    public void deleteFolder(Long accountId, Long folderId) throws CoreException {
        if (accountId == null || folderId == null) throw new CoreException("accountId and folderId required");

        EntityManager em = EntityManagerFactoryProvider.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        try {
            FolderRepository folderRepo = new FolderRepositoryImpl(em);

            tx.begin();

            FolderEntity folder = folderRepo.findById(folderId)
                    .orElseThrow(() -> new NotFoundException("Folder not found with id: " + folderId));

            if (!folder.getAccountId().equals(accountId)) {
                throw new CoreException("Folder does not belong to account");
            }

            // optional: prevent deletion if messages exist
            // if (messageRepository != null) {
            //     long cnt = messageRepository.countByFolder(folderId);
            //     if (cnt > 0) throw new CoreException("Folder contains messages, cannot delete");
            // }

            folderRepo.delete(folder);
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw new CoreException("Failed to delete folder: " + e.getMessage(), e);
        } finally {
            if (em != null && em.isOpen()) em.close();
        }
    }

    @Override
    public FolderDTO renameFolder(Long accountId, Long folderId, String newName) throws CoreException {
        if (accountId == null || folderId == null) throw new CoreException("accountId and folderId required");
        if (newName == null || newName.isBlank()) throw new CoreException("newName required");

        EntityManager em = EntityManagerFactoryProvider.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        try {
            FolderRepository folderRepo = new FolderRepositoryImpl(em);

            tx.begin();

            FolderEntity folder = folderRepo.findById(folderId)
                    .orElseThrow(() -> new NotFoundException("Folder not found with id: " + folderId));

            if (!folder.getAccountId().equals(accountId)) {
                throw new CoreException("Folder does not belong to account");
            }

            folder.setLocalName(newName);
            FolderEntity updated = folderRepo.save(folder);

            tx.commit();

            return FolderMapper.toDto(FolderMapper.toDomain(updated));
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw new CoreException("Failed to rename folder: " + e.getMessage(), e);
        } finally {
            if (em != null && em.isOpen()) em.close();
        }
    }

    @Override
    public void setLastSyncUid(Long accountId, Long folderId, String lastSyncUid) throws CoreException {
        if (accountId == null || folderId == null) throw new CoreException("accountId and folderId required");

        EntityManager em = EntityManagerFactoryProvider.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        try {
            FolderRepository folderRepo = new FolderRepositoryImpl(em);

            tx.begin();

            FolderEntity folder = folderRepo.findById(folderId)
                    .orElseThrow(() -> new NotFoundException("Folder not found with id: " + folderId));

            if (!folder.getAccountId().equals(accountId)) {
                throw new CoreException("Folder does not belong to account");
            }

            if (lastSyncUid == null || lastSyncUid.isBlank()) {
                folder.setLastSyncUid(null);
            } else {
                try {
                    folder.setLastSyncUid(Long.parseLong(lastSyncUid));
                } catch (NumberFormatException nfe) {
                    // treat invalid value as null, but can also throw
                    folder.setLastSyncUid(null);
                }
            }

            folderRepo.save(folder);
            tx.commit();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw new CoreException("Failed to set last sync UID: " + e.getMessage(), e);
        } finally {
            if (em != null && em.isOpen()) em.close();
        }
    }
}
