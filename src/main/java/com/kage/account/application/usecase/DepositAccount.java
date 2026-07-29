package com.kage.account.application.usecase;

import com.kage.account.domain.entity.Account;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.exception.ValidationException;
import com.kage.shared.domain.valueobject.Money;

import java.math.BigDecimal;
import java.util.UUID;

public class DepositAccount {

    public record Input(UUID accountId, BigDecimal amount) {}
    public record Output(UUID accountId, BigDecimal balance, BigDecimal availableBalance) {}

    private final AccountRepository accountRepository;

    public DepositAccount(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    public Output execute(Input input) {
        if (input.amount() == null || input.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ValidationException("Valor do depósito deve ser maior que zero");
        }

        Account account = accountRepository.findById(input.accountId())
                .orElseThrow(() -> new NotFoundException("Conta não encontrada"));

        account.credit(new Money(input.amount()));
        Account saved = accountRepository.save(account);

        return new Output(saved.getId(), saved.getBalance().amount(), saved.getAvailableBalance().amount());    }
}