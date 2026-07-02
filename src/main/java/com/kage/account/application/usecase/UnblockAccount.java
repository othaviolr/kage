package com.kage.account.application.usecase;

import com.kage.account.domain.entity.Account;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.shared.domain.exception.NotFoundException;

import java.util.UUID;

public class UnblockAccount {

    public record Input(UUID accountId) {}
    public record Output(UUID accountId, String status) {}

    private final AccountRepository accountRepository;

    public UnblockAccount(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    public Output execute(Input input) {
        Account account = accountRepository.findById(input.accountId())
                .orElseThrow(() -> new NotFoundException("Conta não encontrada"));

        account.unblock();
        accountRepository.save(account);

        return new Output(account.getId(), account.getStatus().name());
    }
}