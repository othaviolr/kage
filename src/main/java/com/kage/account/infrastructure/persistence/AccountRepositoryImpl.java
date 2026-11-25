package com.kage.account.infrastructure.persistence;

import com.kage.account.domain.entity.Account;
import com.kage.account.domain.repository.AccountRepository;

import java.util.Optional;
import java.util.UUID;

public class AccountRepositoryImpl implements AccountRepository {

    private final AccountJpaRepository accountJpaRepository;

    public AccountRepositoryImpl(AccountJpaRepository accountJpaRepository) {
        this.accountJpaRepository = accountJpaRepository;
    }

    @Override
    public Account save(Account account) {
        AccountJpaEntity entity = AccountMapper.toJpaEntity(account);
        AccountJpaEntity saved = accountJpaRepository.save(entity);
        return AccountMapper.toDomain(saved);
    }

    @Override
    public Optional<Account> findById(UUID id) {
        return accountJpaRepository.findById(id).map(AccountMapper::toDomain);
    }

    @Override
    public Optional<Account> findByIdForUpdate(UUID id) {
        return accountJpaRepository.findByIdForUpdate(id).map(AccountMapper::toDomain);
    }

    @Override
    public Optional<Account> findByAccountNumber(String accountNumber) {
        return accountJpaRepository.findByAccountNumber(accountNumber).map(AccountMapper::toDomain);
    }

    @Override
    public String nextAccountNumber() {
        return String.valueOf(accountJpaRepository.nextAccountNumber());
    }
}