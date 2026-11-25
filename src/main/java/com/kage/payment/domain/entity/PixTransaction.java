package com.kage.payment.domain.entity;

import com.kage.payment.domain.enums.PixTransactionStatus;
import com.kage.payment.domain.enums.TransactionType;
import com.kage.shared.domain.exception.DomainException;
import com.kage.shared.domain.valueobject.Money;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public class PixTransaction {

    private final UUID transactionId;
    private final UUID idempotencyKey;
    private final UUID sourceAccountId;
    private final String targetPixKey;
    private UUID targetAccountId;
    private final Money amount;
    private final String description;
    private final TransactionType transactionType;
    private final LocalDateTime scheduledDate;
    private PixTransactionStatus status;
    private String failureReason;
    private final String e2eId;
    private final LocalDateTime createdAt;
    private LocalDateTime processedAt;
    private LocalDateTime completedAt;
    private final UUID recurrenceId;
    private final Integer occurrenceNumber;

    private PixTransaction(UUID transactionId, UUID idempotencyKey, UUID sourceAccountId,
                           String targetPixKey, UUID targetAccountId, Money amount,
                           String description, TransactionType transactionType,
                           LocalDateTime scheduledDate, PixTransactionStatus status,
                           String failureReason, String e2eId, LocalDateTime createdAt,
                           LocalDateTime processedAt, LocalDateTime completedAt,
                           UUID recurrenceId, Integer occurrenceNumber) {
        this.recurrenceId = recurrenceId;
        this.occurrenceNumber = occurrenceNumber;
        this.transactionId = transactionId;
        this.idempotencyKey = idempotencyKey;
        this.sourceAccountId = sourceAccountId;
        this.targetPixKey = targetPixKey;
        this.targetAccountId = targetAccountId;
        this.amount = amount;
        this.description = description;
        this.transactionType = transactionType;
        this.scheduledDate = scheduledDate;
        this.status = status;
        this.failureReason = failureReason;
        this.e2eId = e2eId;
        this.createdAt = createdAt;
        this.processedAt = processedAt;
        this.completedAt = completedAt;
    }

    public static PixTransaction create(UUID sourceAccountId, String targetPixKey,
                                        Money amount, String description,
                                        TransactionType transactionType,
                                        LocalDateTime scheduledDate) {
        return create(sourceAccountId, targetPixKey, amount, description, transactionType, scheduledDate, null, null);
    }

    private static PixTransaction create(UUID sourceAccountId, String targetPixKey, Money amount, String description,
                                         TransactionType transactionType, LocalDateTime scheduledDate,
                                         UUID recurrenceId, Integer occurrenceNumber) {
        return new PixTransaction(
                UUID.randomUUID(),
                UUID.randomUUID(),
                sourceAccountId,
                targetPixKey,
                null,
                amount,
                description,
                transactionType,
                scheduledDate,
                PixTransactionStatus.PENDING,
                null,
                generateE2eId(),
                LocalDateTime.now(),
                null,
                null,
                recurrenceId,
                occurrenceNumber
        );
    }

    /** Máximo de antecedência para agendar. */
    public static final int MAX_SCHEDULE_DAYS = 365;

    /**
     * PIX agendado: fica PENDING até o dia, sem tocar em saldo nem em limite — ambos são conferidos
     * na execução, como nos bancos (dá para agendar hoje sem ter o dinheiro ainda).
     */
    public static PixTransaction schedule(UUID sourceAccountId, String targetPixKey, Money amount, String description,
                                          LocalDate scheduledDate, LocalDate today) {
        if (scheduledDate == null) throw new DomainException("Data do agendamento é obrigatória");
        if (!scheduledDate.isAfter(today)) {
            throw new DomainException("Agendamento precisa ser para uma data futura; para hoje, use o PIX imediato");
        }
        if (scheduledDate.isAfter(today.plusDays(MAX_SCHEDULE_DAYS))) {
            throw new DomainException("Agendamento pode ser feito com até " + MAX_SCHEDULE_DAYS + " dias de antecedência");
        }
        if (amount == null || amount.isZero()) throw new DomainException("Valor do PIX deve ser maior que zero");
        return create(sourceAccountId, targetPixKey, amount, description, TransactionType.SCHEDULED, scheduledDate.atStartOfDay());
    }

    /**
     * Ocorrência {@code n} de um PIX recorrente: um PIX agendado comum, só que sem a checagem de
     * data futura — se o job ficou parado, a ocorrência atrasada sai na próxima rodada.
     */
    public static PixTransaction occurrenceOf(PixRecurrence recurrence, int n) {
        return create(recurrence.getSourceAccountId(), recurrence.getTargetPixKey(), recurrence.getAmount(),
                recurrence.getDescription(), TransactionType.SCHEDULED, recurrence.dateOf(n).atStartOfDay(),
                recurrence.getId(), n);
    }

    public boolean isScheduled() {
        return transactionType == TransactionType.SCHEDULED;
    }

    public boolean isRecurring() {
        return recurrenceId != null;
    }

    public static PixTransaction reconstitute(UUID transactionId, UUID idempotencyKey,
                                              UUID sourceAccountId, String targetPixKey,
                                              UUID targetAccountId, Money amount,
                                              String description, TransactionType transactionType,
                                              LocalDateTime scheduledDate, PixTransactionStatus status,
                                              String failureReason, String e2eId,
                                              LocalDateTime createdAt, LocalDateTime processedAt,
                                              LocalDateTime completedAt, UUID recurrenceId,
                                              Integer occurrenceNumber) {
        return new PixTransaction(transactionId, idempotencyKey, sourceAccountId, targetPixKey,
                targetAccountId, amount, description, transactionType, scheduledDate,
                status, failureReason, e2eId, createdAt, processedAt, completedAt, recurrenceId, occurrenceNumber);
    }

    public void startProcessing(UUID targetAccountId) {
        if (this.status != PixTransactionStatus.PENDING) {
            throw new DomainException("Transação não está pendente");
        }
        this.status = PixTransactionStatus.PROCESSING;
        this.targetAccountId = targetAccountId;
        this.processedAt = LocalDateTime.now();
    }

    public void complete() {
        if (this.status != PixTransactionStatus.PROCESSING) {
            throw new DomainException("Transação não está em processamento");
        }
        this.status = PixTransactionStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
    }

    public void fail(String reason) {
        if (this.status == PixTransactionStatus.COMPLETED ||
                this.status == PixTransactionStatus.CANCELLED) {
            throw new DomainException("Transação não pode ser marcada como falha");
        }
        this.status = PixTransactionStatus.FAILED;
        this.failureReason = reason;
        this.completedAt = LocalDateTime.now();
    }

    public void cancel() {
        if (this.status != PixTransactionStatus.PENDING) {
            throw new DomainException("Apenas transações pendentes podem ser canceladas");
        }
        this.status = PixTransactionStatus.CANCELLED;
        this.completedAt = LocalDateTime.now();
    }

    private static String generateE2eId() {
        return "E2E" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
    }

    // Getters
    public UUID getTransactionId() { return transactionId; }
    public UUID getIdempotencyKey() { return idempotencyKey; }
    public UUID getSourceAccountId() { return sourceAccountId; }
    public String getTargetPixKey() { return targetPixKey; }
    public UUID getTargetAccountId() { return targetAccountId; }
    public Money getAmount() { return amount; }
    public String getDescription() { return description; }
    public TransactionType getTransactionType() { return transactionType; }
    public LocalDateTime getScheduledDate() { return scheduledDate; }
    public PixTransactionStatus getStatus() { return status; }
    public String getFailureReason() { return failureReason; }
    public String getE2eId() { return e2eId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getProcessedAt() { return processedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public UUID getRecurrenceId() { return recurrenceId; }
    public Integer getOccurrenceNumber() { return occurrenceNumber; }
}