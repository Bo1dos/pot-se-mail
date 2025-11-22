package ru.study.persistence.repository.impl;

import ru.study.persistence.entity.FolderEntity;
import ru.study.persistence.repository.api.FolderRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.util.List;
import java.util.Optional;

public class FolderRepositoryImpl implements FolderRepository {
    private final EntityManager em;

    public FolderRepositoryImpl(EntityManager em) { this.em = em; }

    @Override
    public FolderEntity save(FolderEntity folder) {
        if (folder.getId() == null) {
            em.persist(folder);
            return folder;
        } else {
            return em.merge(folder);
        }
    }

    @Override
    public Optional<FolderEntity> findById(Long id) {
        return Optional.ofNullable(em.find(FolderEntity.class, id));
    }

    @Override
    public List<FolderEntity> findAll() {
        TypedQuery<FolderEntity> q = em.createQuery(
            "SELECT f FROM FolderEntity f", FolderEntity.class);
        return q.getResultList();
    }

    @Override
    public List<FolderEntity> findAll(int limit, int offset) {
        TypedQuery<FolderEntity> q = em.createQuery(
            "SELECT f FROM FolderEntity f ORDER BY f.id", FolderEntity.class);
        q.setFirstResult(offset);
        q.setMaxResults(limit);
        return q.getResultList();
    }

    @Override
    public Optional<FolderEntity> findByAccountAndServerName(Long accountId, String serverName) {
        TypedQuery<FolderEntity> q = em.createQuery(
            "SELECT f FROM FolderEntity f WHERE f.account.id = :aid AND f.serverName = :sname", FolderEntity.class);
        q.setParameter("aid", accountId);
        q.setParameter("sname", serverName);
        return q.getResultStream().findFirst();
    }

    @Override
    public List<FolderEntity> findByAccountId(Long accountId) {
        TypedQuery<FolderEntity> q = em.createQuery(
            "SELECT f FROM FolderEntity f WHERE f.account.id = :aid ORDER BY f.serverName", FolderEntity.class);
        q.setParameter("aid", accountId);
        return q.getResultList();
    }

    @Override
    public List<FolderEntity> findByAccountId(Long accountId, int limit, int offset) {
        TypedQuery<FolderEntity> q = em.createQuery(
            "SELECT f FROM FolderEntity f WHERE f.account.id = :aid ORDER BY f.serverName", FolderEntity.class);
        q.setParameter("aid", accountId);
        q.setFirstResult(offset);
        q.setMaxResults(limit);
        return q.getResultList();
    }

    @Override
    public void delete(FolderEntity folder) {
        if (!em.contains(folder)) folder = em.merge(folder);
        em.remove(folder);
    }

    @Override
    public boolean existsById(Long id) {
        TypedQuery<Long> q = em.createQuery(
            "SELECT COUNT(f) FROM FolderEntity f WHERE f.id = :id", Long.class);
        q.setParameter("id", id);
        return q.getSingleResult() > 0;
    }
}