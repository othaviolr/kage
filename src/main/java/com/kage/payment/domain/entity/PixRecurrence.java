package com.kage.payment.domain.entity;

import com.kage.shared.domain.exception.DomainException;
import com.kage.shared.domain.valueobject.Money;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * PIX recorrente: o mesmo PIX toda semana ou todo mês, por um número fixo de vezes (aluguel,
 * mesada, diarista). Só a próxima ocorrência existe como PIX agendado; quando ela é resolvida
 * (enviada, recusada ou pulada pelo cliente), {@link #advance} libera a seguinte. Assim, uma
 * ocorrência sem saldo não cancela as próximas, e saldo e limites são sempre os do dia.
 *
 * As datas saem sempre da data de início, não da ocorrência anterior: mensal começando em 31/01
 * cai em 28/02 (ou 29) e volta a 31/03, como nos bancos.
 */
public class PixRecurrence {

    public enum Frequency { WEEKLY, MONTHLY }
    public enum Status { ACTIVE, FINISHED, CANCELLED }

    public static final int MAX_SCHEDULE_DAYS = PixTransaction.MAX_SCHEDULE_DAYS;
    public static final int MAX_WEEKLY = 52;   // um ano
    public static final int MAX_MONTHLY = 60;  // cinco anos

    private final UUID id;
    private final UUID sourceAccountId;
    private final String targetPixKey;
    private final Money amount;
    private final String description;
    private final Frequency frequency;
    private final LocalDate startDate;
    private final int totalOccurrences;
    private int occurrencesCreated;
    private Status status;
    private final LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private final long version;

    private PixRecurrence(UUID id, UUID sourceAccountId, String targetPixKey, Money amount, String description,
                          Frequency frequency, LocalDate startDate, int totalOccurrences, int occurrencesCreated,
                          Status status, LocalDateTime createdAt, LocalDateTime updatedAt, long version) {
        this.id = id;
        this.sourceAccountId = sourceAccountId;
        this.targetPixKey = targetPixKey;
        this.amount = amount;
        this.description = description;
        this.frequency = frequency;
        this.startDate = startDate;
        this.totalOccurrences = totalOccurrences;
        this.occurrencesCreated = occurrencesCreated;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    public static PixRecurrence create(UUID sourceAccountId, String targetPixKey, Money amount, String description,
                                       Frequency frequency, LocalDate startDate, int totalOccurrences,
                                       LocalDate today, LocalDateTime now) {
        if (frequency == null) throw new DomainException("Frequência é obrigatória (WEEKLY ou MONTHLY)");
        if (amount == null || amount.isZero()) throw new DomainException("Valor do PIX deve ser maior que zero");
        if (startDate == null) throw new DomainException("Data da primeira ocorrência é obrigatória");
        if (!startDate.isAfter(today)) {
            throw new DomainException("A primeira ocorrência precisa ser numa data futura");
        }
        if (startDate.isAfter(today.plusDays(MAX_SCHEDULE_DAYS))) {
            throw new DomainException("A primeira ocorrência pode ser em até " + MAX_SCHEDULE_DAYS + " dias");
        }
        int max = frequency == Frequency.WEEKLY ? MAX_WEEKLY : MAX_MONTHLY;
        if (totalOccurrences < 2 || totalOccurrences > max) {
            throw new DomainException("PIX " + (frequency == Frequency.WEEKLY ? "semanal" : "mensal")
                    + " pode ter de 2 a " + max + " ocorrências");
        }
        return new PixRecurrence(UUID.randomUUID(), sourceAccountId, targetPixKey, amount, description, frequency,
                startDate, totalOccurrences, 0, Status.ACTIVE, now, now, 0);
    }

    public static PixRecurrence reconstitute(UUID id, UUID sourceAccountId, String targetPixKey, Money amount,
                                             String description, Frequency frequency, LocalDate startDate,
                                             int totalOccurrences, int occurrencesCreated, Status status,
                                             LocalDateTime createdAt, LocalDateTime updatedAt, long version) {
        return new PixRecurrence(id, sourceAccountId, targetPixKey, amount, description, frequency, startDate,
                totalOccurrences, occurrencesCreated, status, createdAt, updatedAt, version);
    }

    /** Data da ocorrência {@code n} (1 = a primeira). */
    public LocalDate dateOf(int n) {
        if (n < 1 || n > totalOccurrences) throw new IllegalArgumentException("Ocorrência fora da recorrência: " + n);
        return frequency == Frequency.WEEKLY ? startDate.plusWeeks(n - 1L) : startDate.plusMonths(n - 1L);
    }

    /**
     * A ocorrência atual foi resolvida: libera o número da próxima, ou encerra a recorrência se
     * aquela era a última. Recorrência cancelada não anda mais.
     */
    public OptionalInt advance(LocalDateTime now) {
        if (status != Status.ACTIVE) return OptionalInt.empty();
        updatedAt = now;
        if (occurrencesCreated >= totalOccurrences) {
            status = Status.FINISHED;
            return OptionalInt.empty();
        }
        return OptionalInt.of(++occurrencesCreated);
    }

    public void cancel(LocalDateTime now) {
        if (status != Status.ACTIVE) throw new DomainException("Só uma recorrência ativa pode ser cancelada");
        status = Status.CANCELLED;
        updatedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getSourceAccountId() { return sourceAccountId; }
    public String getTargetPixKey() { return targetPixKey; }
    public Money getAmount() { return amount; }
    public String getDescription() { return description; }
    public Frequency getFrequency() { return frequency; }
    public LocalDate getStartDate() { return startDate; }
    public int getTotalOccurrences() { return totalOccurrences; }
    public int getOccurrencesCreated() { return occurrencesCreated; }
    public Status getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
