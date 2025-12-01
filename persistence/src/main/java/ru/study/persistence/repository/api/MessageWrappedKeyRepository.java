package ru.study.persistence.repository.api;

import ru.study.persistence.entity.MessageWrappedKeyEntity;
import java.util.List;
import java.util.Optional;

public interface MessageWrappedKeyRepository extends CrudRepository<MessageWrappedKeyEntity, Long> {
    
    List<MessageWrappedKeyEntity> findByMessageId(Long messageId);
    
    List<MessageWrappedKeyEntity> findByRecipient(String recipient);
    
    Optional<MessageWrappedKeyEntity> findByMessageAndRecipient(Long messageId, String recipient);
    
    void deleteByMessageId(Long messageId);
    
    boolean existsByMessageIdAndRecipient(Long messageId, String recipient);
}