package ru.study.persistence.repository.impl;

import ru.study.persistence.entity.MessageEntity;
import ru.study.persistence.repository.api.MessageRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.util.List;
import java.util.Optional;

public class MessageRepositoryImpl implements MessageRepository {
    private final EntityManager em;

    public MessageRepositoryImpl(EntityManager em) { this.em = em; }

    @Override
    public MessageEntity save(MessageEntity m) {
        if (m.getId() == null) {
            em.persist(m);
            return m;
        } else {
            return em.merge(m);
        }
    }

    @Override
    public Optional<MessageEntity> findById(Long id) {
        return Optional.ofNullable(em.find(MessageEntity.class, id));
    }

    @Override
    public List<MessageEntity> findAll() {
        TypedQuery<MessageEntity> q = em.createQuery(
            "SELECT m FROM MessageEntity m", MessageEntity.class);
        return q.getResultList();
    }

    @Override
    public List<MessageEntity> findAll(int limit, int offset) {
        TypedQuery<MessageEntity> q = em.createQuery(
            "SELECT m FROM MessageEntity m ORDER BY m.sentDate DESC", MessageEntity.class);
        q.setFirstResult(offset);
        q.setMaxResults(limit);
        return q.getResultList();
    }

    @Override
    public Optional<MessageEntity> findByAccountAndServerUid(Long accountId, String serverUid) {
        TypedQuery<MessageEntity> q = em.createQuery(
            "SELECT m FROM MessageEntity m WHERE m.account.id = :aid AND m.serverUid = :uid", MessageEntity.class);
        q.setParameter("aid", accountId);
        q.setParameter("uid", serverUid);
        return q.getResultStream().findFirst();
    }

    @Override
    public List<MessageEntity> findByFolder(Long folderId, int limit, int offset) {
        TypedQuery<MessageEntity> q = em.createQuery(
            "SELECT m FROM MessageEntity m WHERE m.folder.id = :fid ORDER BY m.sentDate DESC",
            MessageEntity.class);
        q.setParameter("fid", folderId);
        q.setFirstResult(offset);
        q.setMaxResults(limit);
        return q.getResultList();
    }

    @Override
    public List<MessageEntity> findByAccount(Long accountId, int limit, int offset) {
        TypedQuery<MessageEntity> q = em.createQuery(
            "SELECT m FROM MessageEntity m WHERE m.account.id = :aid ORDER BY m.sentDate DESC",
            MessageEntity.class);
        q.setParameter("aid", accountId);
        q.setFirstResult(offset);
        q.setMaxResults(limit);
        return q.getResultList();
    }

    @Override
    public long countByFolder(Long folderId) {
        TypedQuery<Long> q = em.createQuery(
            "SELECT COUNT(m) FROM MessageEntity m WHERE m.folder.id = :fid", Long.class);
        q.setParameter("fid", folderId);
        return q.getSingleResult();
    }

    @Override
    public long countByAccount(Long accountId) {
        TypedQuery<Long> q = em.createQuery(
            "SELECT COUNT(m) FROM MessageEntity m WHERE m.account.id = :aid", Long.class);
        q.setParameter("aid", accountId);
        return q.getSingleResult();
    }

    @Override
    public void delete(MessageEntity message) {
        if (!em.contains(message)) message = em.merge(message);
        em.remove(message);
    }

    @Override
    public boolean existsById(Long id) {
        TypedQuery<Long> q = em.createQuery(
            "SELECT COUNT(m) FROM MessageEntity m WHERE m.id = :id", Long.class);
        q.setParameter("id", id);
        return q.getSingleResult() > 0;
    }
}