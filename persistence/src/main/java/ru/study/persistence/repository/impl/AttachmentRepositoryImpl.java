package ru.study.persistence.repository.impl;

import ru.study.persistence.entity.AttachmentEntity;
import ru.study.persistence.repository.api.AttachmentRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.EntityTransaction;
import java.util.List;
import java.util.Optional;

public class AttachmentRepositoryImpl implements AttachmentRepository {
    private final EntityManager em;

    public AttachmentRepositoryImpl(EntityManager em) { this.em = em; }

    @Override
    public AttachmentEntity save(AttachmentEntity attachment) {
        EntityTransaction tx = em.getTransaction();
        boolean managedTx = false;
        try {
            if (!tx.isActive()) { tx.begin(); managedTx = true; }

            if (attachment.getId() == null) {
                em.persist(attachment);
                em.flush();
            } else {
                attachment = em.merge(attachment);
            }

            if (managedTx) tx.commit();
            return attachment;
        } catch (RuntimeException ex) {
            if (managedTx && tx.isActive()) tx.rollback();
            throw ex;
        }
    }

    @Override
    public Optional<AttachmentEntity> findById(Long id) {
        return Optional.ofNullable(em.find(AttachmentEntity.class, id));
    }

    @Override
    public List<AttachmentEntity> findAll() {
        TypedQuery<AttachmentEntity> q = em.createQuery(
            "SELECT a FROM AttachmentEntity a", AttachmentEntity.class);
        return q.getResultList();
    }

    @Override
    public List<AttachmentEntity> findAll(int limit, int offset) {
        TypedQuery<AttachmentEntity> q = em.createQuery(
            "SELECT a FROM AttachmentEntity a ORDER BY a.id", AttachmentEntity.class);
        q.setFirstResult(offset);
        q.setMaxResults(limit);
        return q.getResultList();
    }

    @Override
    public List<AttachmentEntity> findByMessageId(Long messageId) {
        TypedQuery<AttachmentEntity> q = em.createQuery(
            "SELECT a FROM AttachmentEntity a WHERE a.message.id = :mid", AttachmentEntity.class);
        q.setParameter("mid", messageId);
        return q.getResultList();
    }

    @Override
    public void delete(AttachmentEntity attachment) {
        EntityTransaction tx = em.getTransaction();
        boolean managedTx = false;
        try {
            if (!tx.isActive()) { tx.begin(); managedTx = true; }
            if (!em.contains(attachment)) attachment = em.merge(attachment);
            em.remove(attachment);
            if (managedTx) tx.commit();
        } catch (RuntimeException ex) {
            if (managedTx && tx.isActive()) tx.rollback();
            throw ex;
        }
    }

    @Override
    public boolean existsById(Long id) {
        TypedQuery<Long> q = em.createQuery(
            "SELECT COUNT(a) FROM AttachmentEntity a WHERE a.id = :id", Long.class);
        q.setParameter("id", id);
        return q.getSingleResult() > 0;
    }
}
