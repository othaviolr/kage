package com.kage.account.application.usecase;

import com.kage.account.domain.entity.Account;
import com.kage.account.domain.repository.AccountEntryRepository;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.exception.ValidationException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Extrato da conta num período de datas (inclusivo). Sem datas, devolve os últimos 30 dias. Valores
 * de débito saem negativos, como num extrato de banco.
 */
public class GetStatement {

    static final int DEFAULT_DAYS = 30;
    static final int MAX_DAYS = 90;

    public record Input(UUID accountId, LocalDate from, LocalDate to) {}
    public record EntryOutput(UUID entryId, String type, BigDecimal amount, BigDecimal balanceAfter,
                              UUID referenceId, LocalDateTime createdAt) {}
    public record Output(UUID accountId, LocalDate from, LocalDate to, BigDecimal currentBalance,
                         List<EntryOutput> entries) {}

    private final AccountRepository accountRepository;
    private final AccountEntryRepository entryRepository;
    private final Clock clock;

    public GetStatement(AccountRepository accountRepository, AccountEntryRepository entryRepository, Clock clock) {
        this.accountRepository = accountRepository;
        this.entryRepository = entryRepository;
        this.clock = clock;
    }

    public Output execute(Input input) {
        LocalDate to = input.to() != null ? input.to() : LocalDate.now(clock);
        LocalDate from = input.from() != null ? input.from() : to.minusDays(DEFAULT_DAYS - 1);

        if (from.isAfter(to)) {
            throw new ValidationException("Data inicial não pode ser posterior à data final");
        }
        if (ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
            throw new ValidationException("Período máximo do extrato é de " + MAX_DAYS + " dias");
        }

        Account account = accountRepository.findById(input.accountId())
                .orElseThrow(() -> new NotFoundException("Conta não encontrada"));

        List<EntryOutput> entries = entryRepository
                .findByAccountIdBetween(account.getId(), from.atStartOfDay(), to.plusDays(1).atStartOfDay())
                .stream()
                .map(entry -> new EntryOutput(
                        entry.id(),
                        entry.type().name(),
                        entry.type().isCredit() ? entry.amount().amount() : entry.amount().amount().negate(),
                        entry.balanceAfter().amount(),
                        entry.referenceId(),
                        entry.createdAt()))
                .toList();

        return new Output(account.getId(), from, to, account.getBalance().amount(), entries);
    }
}
