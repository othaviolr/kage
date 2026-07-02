package com.kage.account.application.usecase;

import com.kage.account.domain.entity.Account;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.shared.domain.exception.NotFoundException;

import java.math.BigDecimal;
import java.util.UUID;

public class GetAccount {

    public record Input(UUID accountId) {}
    public record Output(UUID accountId, UUID customerId, String accountNumber, String accountDigit,
                         String branch, String type, BigDecimal balance, BigDecimal availableBalance, String status) {}

    private final AccountRepository accountRepository;

    public GetAccount(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    public Output execute(Input input) {
        Account account = accountRepository.findById(input.accountId())
                .orElseThrow(() -> new NotFoundException("Conta não encontrada"));

        return new Output(
                account.getId(),
                account.getCustomerId(),
                account.getAccountNumber(),
                account.getAccountDigit(),
                account.getBranch(),
                account.getType().name(),
                account.getBalance().amount(),
                account.getAvailableBalance().amount(),
                account.getStatus().name()
        );
    }
}