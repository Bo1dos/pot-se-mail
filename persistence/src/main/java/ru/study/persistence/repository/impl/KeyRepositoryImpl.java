package ru.study.persistence.repository.impl;

import ru.study.persistence.entity.KeyEntity;
import ru.study.persistence.repository.api.KeyRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.util.List;
import java.util.Optional;

public class KeyRepositoryImpl implements KeyRepository {
    private final EntityManager em;

    public KeyRepositoryImpl(EntityManager em) { this.em = em; }

    @Override
    public KeyEntity save(KeyEntity key) {
        if (key.getId() == null) {
            em.persist(key);
            return key;
        } else {
            return em.merge(key);
        }
    }

    @Override
    public Optional<KeyEntity> findById(Long id) {
        return Optional.ofNullable(em.find(KeyEntity.class, id));
    }

    @Override
    public List<KeyEntity> findAll() {
        TypedQuery<KeyEntity> q = em.createQuery(
            "SELECT k FROM KeyEntity k", KeyEntity.class);
        return q.getResultList();
    }

    @Override
    public List<KeyEntity> findAll(int limit, int offset) {
        TypedQuery<KeyEntity> q = em.createQuery(
            "SELECT k FROM KeyEntity k ORDER BY k.id", KeyEntity.class);
        q.setFirstResult(offset);
        q.setMaxResults(limit);
        return q.getResultList();
    }

    @Override
    public List<KeyEntity> findByAccountId(Long accountId) {
        TypedQuery<KeyEntity> q = em.createQuery(
            "SELECT k FROM KeyEntity k WHERE k.account.id = :aid", KeyEntity.class);
        q.setParameter("aid", accountId);
        return q.getResultList();
    }

    @Override
    public Optional<KeyEntity> findPrimaryByAccountId(Long accountId) {
        TypedQuery<KeyEntity> q = em.createQuery(
            "SELECT k FROM KeyEntity k WHERE k.account.id = :aid ORDER BY k.id LIMIT 1", KeyEntity.class);
        q.setParameter("aid", accountId);
        return q.getResultStream().findFirst();
    }

    @Override
    public void delete(KeyEntity key) {
        if (!em.contains(key)) key = em.merge(key);
        em.remove(key);
    }

    @Override
    public void deleteByAccountId(Long accountId) {
        TypedQuery<KeyEntity> q = em.createQuery(
            "SELECT k FROM KeyEntity k WHERE k.account.id = :aid", KeyEntity.class);
        q.setParameter("aid", accountId);
        List<KeyEntity> keys = q.getResultList();
        keys.forEach(this::delete);
    }

    @Override
    public boolean existsById(Long id) {
        TypedQuery<Long> q = em.createQuery(
            "SELECT COUNT(k) FROM KeyEntity k WHERE k.id = :id", Long.class);
        q.setParameter("id", id);
        return q.getSingleResult() > 0;
    }
}