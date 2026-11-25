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
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Saque com limite diário acumulado. Dois saques simultâneos na mesma conta não furam o limite: os
 * dois atualizam a mesma linha de accounts e o locking otimista (@Version) derruba o segundo.
 */
public class WithdrawAccount {

    public record Input(UUID accountId, BigDecimal amount) {}

    public record Output(UUID accountId, BigDecimal balance, BigDecimal availableBalance) {}

    private final AccountRepository accountRepository;
    private final AccountEntryRepository entryRepository;
    private final AccountEventPublisher eventPublisher;
    private final Clock clock;

    public WithdrawAccount(AccountRepository accountRepository, AccountEntryRepository entryRepository,
                           AccountEventPublisher eventPublisher, Clock clock) {
        this.accountRepository = accountRepository;
        this.entryRepository = entryRepository;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Transactional
    public Output execute(Input input) {
        if (input.amount() == null || input.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ValidationException("Valor do saque deve ser maior que zero");
        }

        Account account = accountRepository.findById(input.accountId())
                .orElseThrow(() -> new NotFoundException("Conta não encontrada"));

        Money withdrawnToday = entryRepository.sumByTypeSince(account.getId(), EntryType.WITHDRAWAL,
                LocalDate.now(clock).atStartOfDay());

        Money amount = new Money(input.amount());
        account.withdraw(amount, withdrawnToday);
        Account saved = accountRepository.save(account);
        entryRepository.save(AccountEntry.of(saved, EntryType.WITHDRAWAL, amount, null));

        eventPublisher.publishWithdrawalMade(saved.getId(), input.amount(), saved.getBalance().amount());

        return new Output(saved.getId(), saved.getBalance().amount(), saved.getAvailableBalance().amount());
    }
}
