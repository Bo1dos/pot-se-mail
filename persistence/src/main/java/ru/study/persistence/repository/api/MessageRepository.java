package ru.study.persistence.repository.api;

import ru.study.persistence.entity.MessageEntity;
import java.util.List;
import java.util.Optional;

public interface MessageRepository extends CrudRepository<MessageEntity, Long> {
    Optional<MessageEntity> findByAccountAndServerUid(Long accountId, String serverUid);
    List<MessageEntity> findByFolder(Long folderId, int limit, int offset);
    List<MessageEntity> findByAccount(Long accountId, int limit, int offset);
    long countByFolder(Long folderId);
    long countByAccount(Long accountId);
}