package ru.study.persistence.repository.api;

import ru.study.persistence.entity.AccountEntity;
import java.util.Optional;

public interface AccountRepository extends CrudRepository<AccountEntity, Long> {
    Optional<AccountEntity> findByEmail(String email);
    boolean existsByEmail(String email);
}