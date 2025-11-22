package ru.study.persistence.repository.api;

import ru.study.persistence.entity.FolderEntity;
import java.util.List;
import java.util.Optional;

public interface FolderRepository extends CrudRepository<FolderEntity, Long> {
    Optional<FolderEntity> findByAccountAndServerName(Long accountId, String serverName);
    List<FolderEntity> findByAccountId(Long accountId);
    List<FolderEntity> findByAccountId(Long accountId, int limit, int offset);
}