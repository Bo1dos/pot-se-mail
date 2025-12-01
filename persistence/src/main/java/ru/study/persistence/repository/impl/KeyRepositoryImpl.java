package ru.study.persistence.repository.impl;

import ru.study.persistence.entity.KeyEntity;
import ru.study.persistence.repository.api.KeyRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.EntityTransaction;
import java.util.List;
import java.util.Optional;

public class KeyRepositoryImpl implements KeyRepository {
    private final EntityManager em;

    public KeyRepositoryImpl(EntityManager em) { this.em = em; }

    @Override
    public KeyEntity save(KeyEntity key) {
        EntityTransaction tx = em.getTransaction();
        boolean managedTx = false;
        try {
            if (!tx.isActive()) { tx.begin(); managedTx = true; }
            if (key.getId() == null) {
                em.persist(key);
                em.flush();
            } else {
                key = em.merge(key);
            }
            if (managedTx) tx.commit();
            return key;
        } catch (RuntimeException ex) {
            if (managedTx && tx.isActive()) tx.rollback();
            throw ex;
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
            "SELECT k FROM KeyEntity k WHERE k.accountId = :aid", KeyEntity.class);
        q.setParameter("aid", accountId);
        return q.getResultList();
    }

    @Override
    public Optional<KeyEntity> findPrimaryByAccountId(Long accountId) {
        TypedQuery<KeyEntity> q = em.createQuery(
            "SELECT k FROM KeyEntity k WHERE k.accountId = :aid ORDER BY k.id", KeyEntity.class);
        q.setParameter("aid", accountId);
        q.setMaxResults(1);
        return q.getResultStream().findFirst();
    }

    @Override
    public void delete(KeyEntity key) {
        EntityTransaction tx = em.getTransaction();
        boolean managedTx = false;
        try {
            if (!tx.isActive()) { tx.begin(); managedTx = true; }
            if (!em.contains(key)) key = em.merge(key);
            em.remove(key);
            if (managedTx) tx.commit();
        } catch (RuntimeException ex) {
            if (managedTx && tx.isActive()) tx.rollback();
            throw ex;
        }
    }

    @Override
    public void deleteByAccountId(Long accountId) {
        // Do bulk delete inside single transaction for efficiency
        EntityTransaction tx = em.getTransaction();
        try {
            if (!tx.isActive()) tx.begin();
            em.createQuery("DELETE FROM KeyEntity k WHERE k.accountId = :aid")
              .setParameter("aid", accountId)
              .executeUpdate();
            tx.commit();
        } catch (RuntimeException ex) {
            if (tx.isActive()) tx.rollback();
            throw ex;
        }
    }

    @Override
    public boolean existsById(Long id) {
        TypedQuery<Long> q = em.createQuery(
            "SELECT COUNT(k) FROM KeyEntity k WHERE k.id = :id", Long.class);
        q.setParameter("id", id);
        return q.getSingleResult() > 0;
    }
}
