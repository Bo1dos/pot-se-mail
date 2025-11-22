package ru.study.persistence.repository.api;

import ru.study.persistence.entity.AttachmentEntity;

import java.util.List;

public interface AttachmentRepository extends CrudRepository<AttachmentEntity, Long> {
    List<AttachmentEntity> findByMessageId(Long messageId);
}
