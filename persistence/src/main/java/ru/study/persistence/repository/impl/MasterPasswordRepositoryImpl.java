package ru.study.persistence.repository.impl;

import ru.study.persistence.entity.MasterPasswordEntity;
import ru.study.persistence.repository.api.MasterPasswordRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.EntityTransaction;
import java.util.Optional;

public class MasterPasswordRepositoryImpl implements MasterPasswordRepository {
    private final EntityManager em;

    public MasterPasswordRepositoryImpl(EntityManager em) { this.em = em; }

    @Override
    public Optional<MasterPasswordEntity> findAny() {
        TypedQuery<MasterPasswordEntity> q = em.createQuery(
            "SELECT m FROM MasterPasswordEntity m", MasterPasswordEntity.class);
        return q.getResultStream().findFirst();
    }

    @Override
    public MasterPasswordEntity save(MasterPasswordEntity e) {
        EntityTransaction tx = em.getTransaction();
        boolean managedTx = false;
        try {
            if (!tx.isActive()) { tx.begin(); managedTx = true; }
            if (e.getId() == null) {
                em.persist(e);
                em.flush();
            } else {
                e = em.merge(e);
            }
            if (managedTx) tx.commit();
            return e;
        } catch (RuntimeException ex) {
            if (managedTx && tx.isActive()) tx.rollback();
            throw ex;
        }
    }

    @Override
    public void deleteAll() {
        EntityTransaction tx = em.getTransaction();
        try {
            if (!tx.isActive()) tx.begin();
            em.createQuery("DELETE FROM MasterPasswordEntity m").executeUpdate();
            tx.commit();
        } catch (RuntimeException ex) {
            if (tx.isActive()) tx.rollback();
            throw ex;
        }
    }
}
