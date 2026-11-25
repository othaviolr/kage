package com.kage.account.domain.repository;

import com.kage.account.domain.entity.Account;

import java.util.Optional;
import java.util.UUID;

public interface AccountRepository {

    Account save(Account account);
    Optional<Account> findById(UUID id);
    /** Lê a conta com lock pessimista até o fim da transação corrente (exige transação ativa). */
    Optional<Account> findByIdForUpdate(UUID id);
    Optional<Account> findByAccountNumber(String accountNumber);
    boolean existsByAccountNumber(String accountNumber);
}