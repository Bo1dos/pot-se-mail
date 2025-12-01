package ru.study.persistence.repository.impl;

import ru.study.persistence.entity.MessageWrappedKeyEntity;
import ru.study.persistence.repository.api.MessageWrappedKeyRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.EntityTransaction;
import java.util.List;
import java.util.Optional;

public class MessageWrappedKeyRepositoryImpl implements MessageWrappedKeyRepository {
    private final EntityManager em;

    public MessageWrappedKeyRepositoryImpl(EntityManager em) { 
        this.em = em; 
    }

    @Override
    public MessageWrappedKeyEntity save(MessageWrappedKeyEntity key) {
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
    public Optional<MessageWrappedKeyEntity> findById(Long id) {
        return Optional.ofNullable(em.find(MessageWrappedKeyEntity.class, id));
    }

    @Override
    public List<MessageWrappedKeyEntity> findAll() {
        TypedQuery<MessageWrappedKeyEntity> q = em.createQuery(
            "SELECT k FROM MessageWrappedKeyEntity k", MessageWrappedKeyEntity.class);
        return q.getResultList();
    }

    @Override
    public List<MessageWrappedKeyEntity> findAll(int limit, int offset) {
        TypedQuery<MessageWrappedKeyEntity> q = em.createQuery(
            "SELECT k FROM MessageWrappedKeyEntity k ORDER BY k.createdAt DESC", MessageWrappedKeyEntity.class);
        q.setFirstResult(offset);
        q.setMaxResults(limit);
        return q.getResultList();
    }

    @Override
    public List<MessageWrappedKeyEntity> findByMessageId(Long messageId) {
        TypedQuery<MessageWrappedKeyEntity> q = em.createQuery(
            "SELECT k FROM MessageWrappedKeyEntity k WHERE k.message.id = :messageId ORDER BY k.recipient", 
            MessageWrappedKeyEntity.class);
        q.setParameter("messageId", messageId);
        return q.getResultList();
    }

    @Override
    public List<MessageWrappedKeyEntity> findByRecipient(String recipient) {
        TypedQuery<MessageWrappedKeyEntity> q = em.createQuery(
            "SELECT k FROM MessageWrappedKeyEntity k WHERE k.recipient = :recipient ORDER BY k.createdAt DESC", 
            MessageWrappedKeyEntity.class);
        q.setParameter("recipient", recipient);
        return q.getResultList();
    }

    @Override
    public Optional<MessageWrappedKeyEntity> findByMessageAndRecipient(Long messageId, String recipient) {
        TypedQuery<MessageWrappedKeyEntity> q = em.createQuery(
            "SELECT k FROM MessageWrappedKeyEntity k WHERE k.message.id = :messageId AND k.recipient = :recipient", 
            MessageWrappedKeyEntity.class);
        q.setParameter("messageId", messageId);
        q.setParameter("recipient", recipient);
        return q.getResultStream().findFirst();
    }

    @Override
    public void deleteByMessageId(Long messageId) {
        // bulk delete in one transaction
        EntityTransaction tx = em.getTransaction();
        try {
            if (!tx.isActive()) tx.begin();
            em.createQuery("DELETE FROM MessageWrappedKeyEntity k WHERE k.message.id = :mid")
              .setParameter("mid", messageId)
              .executeUpdate();
            tx.commit();
        } catch (RuntimeException ex) {
            if (tx.isActive()) tx.rollback();
            throw ex;
        }
    }

    @Override
    public boolean existsByMessageIdAndRecipient(Long messageId, String recipient) {
        TypedQuery<Long> q = em.createQuery(
            "SELECT COUNT(k) FROM MessageWrappedKeyEntity k WHERE k.message.id = :messageId AND k.recipient = :recipient", 
            Long.class);
        q.setParameter("messageId", messageId);
        q.setParameter("recipient", recipient);
        return q.getSingleResult() > 0;
    }

    @Override
    public void delete(MessageWrappedKeyEntity key) {
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
    public boolean existsById(Long id) {
        TypedQuery<Long> q = em.createQuery(
            "SELECT COUNT(k) FROM MessageWrappedKeyEntity k WHERE k.id = :id", 
            Long.class);
        q.setParameter("id", id);
        return q.getSingleResult() > 0;
    }
}
