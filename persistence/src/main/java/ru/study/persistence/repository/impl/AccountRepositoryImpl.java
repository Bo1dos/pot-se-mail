package ru.study.persistence.repository.impl;

import ru.study.persistence.entity.AccountEntity;
import ru.study.persistence.repository.api.AccountRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.util.List;
import java.util.Optional;

public class AccountRepositoryImpl implements AccountRepository {
    private final EntityManager em;

    public AccountRepositoryImpl(EntityManager em) { this.em = em; }

    @Override
    public AccountEntity save(AccountEntity account) {
        if (account.getId() == null) {
            em.persist(account);
            return account;
        } else {
            return em.merge(account);
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
        if (!em.contains(account)) account = em.merge(account);
        em.remove(account);
    }

    @Override
    public boolean existsById(Long id) {
        TypedQuery<Long> q = em.createQuery(
            "SELECT COUNT(a) FROM AccountEntity a WHERE a.id = :id", Long.class);
        q.setParameter("id", id);
        return q.getSingleResult() > 0;
    }
}