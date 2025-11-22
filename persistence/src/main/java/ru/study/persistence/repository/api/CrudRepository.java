package ru.study.persistence.repository.api;

import java.util.List;
import java.util.Optional;

public interface CrudRepository<T, ID> {
    T save(T entity);
    Optional<T> findById(ID id);
    List<T> findAll();
    List<T> findAll(int limit, int offset);
    void delete(T entity);
    boolean existsById(ID id);
}