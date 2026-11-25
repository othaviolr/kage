package com.kage.account.application.usecase;

import com.kage.account.domain.entity.Account;
import com.kage.account.domain.entity.AccountEntry;
import com.kage.account.domain.enums.EntryType;
import com.kage.account.domain.repository.AccountEntryRepository;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.exception.ValidationException;
import com.kage.shared.domain.valueobject.Money;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

public class DepositAccount {

    public record Input(UUID accountId, BigDecimal amount) {}
    public record Output(UUID accountId, BigDecimal balance, BigDecimal availableBalance) {}

    private final AccountRepository accountRepository;
    private final AccountEntryRepository entryRepository;
    private final AccountEventPublisher eventPublisher;

    public DepositAccount(AccountRepository accountRepository, AccountEntryRepository entryRepository,
                          AccountEventPublisher eventPublisher) {
        this.accountRepository = accountRepository;
        this.entryRepository = entryRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public Output execute(Input input) {
        if (input.amount() == null || input.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ValidationException("Valor do depósito deve ser maior que zero");
        }

        Account account = accountRepository.findById(input.accountId())
                .orElseThrow(() -> new NotFoundException("Conta não encontrada"));

        Money amount = new Money(input.amount());
        account.credit(amount);
        Account saved = accountRepository.save(account);
        entryRepository.save(AccountEntry.of(saved, EntryType.DEPOSIT, amount, null));

        eventPublisher.publishDepositMade(saved.getId(), input.amount(), saved.getBalance().amount());

        return new Output(saved.getId(), saved.getBalance().amount(), saved.getAvailableBalance().amount());
    }
}
