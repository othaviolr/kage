package com.kage.account.application.usecase;

import com.kage.account.domain.entity.Account;
import com.kage.account.domain.repository.AccountEntryRepository;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.exception.ValidationException;
import com.kage.shared.domain.valueobject.Money;

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
 *
 * Saldo inicial = saldo depois do último lançamento antes do período (zero se não havia nenhum);
 * saldo final = depois do último lançamento do período (ou o inicial, se não houve movimento). Saem
 * do ledger, então batem com os lançamentos mesmo para períodos antigos.
 */
public class GetStatement {

    static final int DEFAULT_DAYS = 30;
    /** No app: o extrato rola na tela. */
    static final int MAX_DAYS = 90;
    /** No arquivo exportado: um ano inteiro, para conferir a declaração do IR. */
    public static final int MAX_EXPORT_DAYS = 366;

    public record Input(UUID accountId, LocalDate from, LocalDate to) {}
    public record EntryOutput(UUID entryId, String type, String description, BigDecimal amount, BigDecimal balanceAfter,
                              UUID referenceId, LocalDateTime createdAt) {}
    /** @param entries mais recentes primeiro */
    public record Output(UUID accountId, String branch, String accountNumber, LocalDate from, LocalDate to,
                         BigDecimal openingBalance, BigDecimal closingBalance, BigDecimal currentBalance,
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
        return execute(input, MAX_DAYS);
    }

    /** Para exportar: o mesmo extrato, com período de até {@link #MAX_EXPORT_DAYS} dias. */
    public Output forExport(Input input) {
        return execute(input, MAX_EXPORT_DAYS);
    }

    private Output execute(Input input, int maxDays) {
        LocalDate to = input.to() != null ? input.to() : LocalDate.now(clock);
        LocalDate from = input.from() != null ? input.from() : to.minusDays(DEFAULT_DAYS - 1);

        if (from.isAfter(to)) {
            throw new ValidationException("Data inicial não pode ser posterior à data final");
        }
        if (ChronoUnit.DAYS.between(from, to) >= maxDays) {
            throw new ValidationException("Período máximo do extrato é de " + maxDays + " dias");
        }

        Account account = accountRepository.findById(input.accountId())
                .orElseThrow(() -> new NotFoundException("Conta não encontrada"));

        List<EntryOutput> entries = entryRepository
                .findByAccountIdBetween(account.getId(), from.atStartOfDay(), to.plusDays(1).atStartOfDay())
                .stream()
                .map(entry -> new EntryOutput(
                        entry.id(),
                        entry.type().name(),
                        entry.type().label(),
                        entry.type().isCredit() ? entry.amount().amount() : entry.amount().amount().negate(),
                        entry.balanceAfter().amount(),
                        entry.referenceId(),
                        entry.createdAt()))
                .toList();

        BigDecimal opening = entryRepository.balanceBefore(account.getId(), from.atStartOfDay()).orElse(Money.ZERO).amount();
        BigDecimal closing = entries.isEmpty() ? opening : entries.get(0).balanceAfter();

        return new Output(account.getId(), account.getBranch(), account.getAccountNumber() + "-" + account.getAccountDigit(),
                from, to, opening, closing, account.getBalance().amount(), entries);
    }
}
