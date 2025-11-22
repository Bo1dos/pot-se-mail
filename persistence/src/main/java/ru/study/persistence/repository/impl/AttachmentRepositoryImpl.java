package ru.study.persistence.repository.impl;

import ru.study.persistence.entity.AttachmentEntity;
import ru.study.persistence.repository.api.AttachmentRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.util.List;
import java.util.Optional;

public class AttachmentRepositoryImpl implements AttachmentRepository {
    private final EntityManager em;

    public AttachmentRepositoryImpl(EntityManager em) { this.em = em; }

    @Override
    public AttachmentEntity save(AttachmentEntity attachment) {
        if (attachment.getId() == null) {
            em.persist(attachment);
            return attachment;
        } else {
            return em.merge(attachment);
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
        if (!em.contains(attachment)) attachment = em.merge(attachment);
        em.remove(attachment);
    }

    @Override
    public boolean existsById(Long id) {
        TypedQuery<Long> q = em.createQuery(
            "SELECT COUNT(a) FROM AttachmentEntity a WHERE a.id = :id", Long.class);
        q.setParameter("id", id);
        return q.getSingleResult() > 0;
    }
}