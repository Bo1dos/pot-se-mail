package ru.study.persistence.repository.api;

import ru.study.persistence.entity.MasterPasswordEntity;

import java.util.Optional;

public interface MasterPasswordRepository {
    Optional<MasterPasswordEntity> findAny();
    MasterPasswordEntity save(MasterPasswordEntity e);
    void deleteAll();
}
