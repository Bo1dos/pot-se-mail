package ru.study.persistence.repository.impl;

import ru.study.persistence.entity.AccountEntity;
import ru.study.persistence.repository.api.AccountRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.EntityTransaction;
import java.util.List;
import java.util.Optional;

public class AccountRepositoryImpl implements AccountRepository {
    private final EntityManager em;

    public AccountRepositoryImpl(EntityManager em) { this.em = em; }

    @Override
    public AccountEntity save(AccountEntity account) {
        EntityTransaction tx = em.getTransaction();
        boolean managedTx = false;
        try {
            if (!tx.isActive()) { tx.begin(); managedTx = true; }

            if (account.getId() == null) {
                em.persist(account);
                em.flush(); // ensure ID generated for IDENTITY
            } else {
                account = em.merge(account);
            }

            if (managedTx) tx.commit();
            return account;
        } catch (RuntimeException ex) {
            if (managedTx && tx.isActive()) tx.rollback();
            throw ex;
        }
    }

    @Override
    public Optional<AccountEntity> findById(Long id) {
        return Optional.ofNullable(em.find(AccountEntity.class, id));
    }

    @Override
    public List<AccountEntity> findAll() {
        TypedQuery<AccountEntity> q = em.createQuery(
            "SELECT a FROM AccountEntity a", AccountEntity.class);
        return q.getResultList();
    }

    @Override
    public List<AccountEntity> findAll(int limit, int offset) {
        TypedQuery<AccountEntity> q = em.createQuery(
            "SELECT a FROM AccountEntity a ORDER BY a.id", AccountEntity.class);
        q.setFirstResult(offset);
        q.setMaxResults(limit);
        return q.getResultList();
    }

    @Override
    public Optional<AccountEntity> findByEmail(String email) {
        TypedQuery<AccountEntity> q = em.createQuery(
            "SELECT a FROM AccountEntity a WHERE a.email = :email", AccountEntity.class);
        q.setParameter("email", email);
        return q.getResultStream().findFirst();
    }

    @Override
    public boolean existsByEmail(String email) {
        TypedQuery<Long> q = em.createQuery(
            "SELECT COUNT(a) FROM AccountEntity a WHERE a.email = :email", Long.class);
        q.setParameter("email", email);
        return q.getSingleResult() > 0;
    }

    @Override
    public void delete(AccountEntity account) {
        EntityTransaction tx = em.getTransaction();
        boolean managedTx = false;
        try {
            if (!tx.isActive()) { tx.begin(); managedTx = true; }
            if (!em.contains(account)) account = em.merge(account);
            em.remove(account);
            if (managedTx) tx.commit();
        } catch (RuntimeException ex) {
            if (managedTx && tx.isActive()) tx.rollback();
            throw ex;
        }
    }

    @Override
    public boolean existsById(Long id) {
        TypedQuery<Long> q = em.createQuery(
            "SELECT COUNT(a) FROM AccountEntity a WHERE a.id = :id", Long.class);
        q.setParameter("id", id);
        return q.getSingleResult() > 0;
    }
}
