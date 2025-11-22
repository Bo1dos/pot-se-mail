package ru.study.persistence.repository.api;

import ru.study.persistence.entity.KeyEntity;
import java.util.List;
import java.util.Optional;

public interface KeyRepository extends CrudRepository<KeyEntity, Long> {
    List<KeyEntity> findByAccountId(Long accountId);
    Optional<KeyEntity> findPrimaryByAccountId(Long accountId);
    void deleteByAccountId(Long accountId);
}