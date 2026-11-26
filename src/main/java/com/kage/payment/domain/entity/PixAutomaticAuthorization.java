package com.kage.payment.domain.entity;

import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.DomainException;
import com.kage.shared.domain.exception.ValidationException;
import com.kage.shared.domain.valueobject.Money;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Autorização de PIX Automático (Banco Central, 2025): quem recebe (academia, escola, streaming) pede
 * uma vez, o pagador autoriza no app do banco dele, e daí em diante quem recebe manda uma cobrança por
 * ciclo, que o banco do pagador agenda e paga no vencimento.
 *
 * Regras do arranjo modeladas aqui:
 * - valor fixo, ou variável com um teto opcional por cobrança definido pelo pagador;
 * - uma cobrança por ciclo, enviada de {@value #MIN_DAYS_AHEAD} a {@value #MAX_DAYS_AHEAD} dias antes
 *   do vencimento, dentro do período autorizado;
 * - o pagador cancela uma cobrança até a véspera, e a autorização a qualquer momento; quem recebe
 *   também pode cancelar a autorização.
 */
public class PixAutomaticAuthorization {

    public enum Frequency {
        WEEKLY(0), MONTHLY(1), QUARTERLY(3), SEMIANNUAL(6), ANNUAL(12);

        private final int months;

        Frequency(int months) {
            this.months = months;
        }

        /** Início do ciclo {@code k} (0 = o primeiro), contado sempre a partir do início da autorização. */
        public LocalDate cycleStart(LocalDate start, int k) {
            return this == WEEKLY ? start.plusWeeks(k) : start.plusMonths((long) k * months);
        }
    }

    public enum Status { PENDING, ACTIVE, REJECTED, CANCELLED }

    public enum Party { PAYER, RECEIVER, ADMIN }

    /** Se quem recebe pode tentar de novo uma cobrança recusada por falta de saldo. O pagador vê ao autorizar. */
    public enum RetryPolicy { NONE, THREE_IN_SEVEN_DAYS }

    public static final int MIN_DAYS_AHEAD = 2;
    public static final int MAX_DAYS_AHEAD = 10;
    public static final int MAX_START_DAYS = 365;
    public static final int MAX_RETRIES = 3;
    public static final int RETRY_WINDOW_DAYS = 7;

    private final UUID id;
    private final UUID payerAccountId;
    private final UUID receiverAccountId;
    private final String receiverPixKey;
    private final String receiverName;
    private final String description;
    private final String contractReference;
    private final Frequency frequency;
    private final Money fixedAmount;
    private final RetryPolicy retryPolicy;
    private Money maxAmount;
    private final LocalDate startDate;
    private final LocalDate endDate;
    private Status status;
    private Party cancelledBy;
    private final LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private final long version;

    private PixAutomaticAuthorization(UUID id, UUID payerAccountId, UUID receiverAccountId, String receiverPixKey,
                                      String receiverName, String description, String contractReference,
                                      Frequency frequency, Money fixedAmount, RetryPolicy retryPolicy, Money maxAmount,
                                      LocalDate startDate, LocalDate endDate, Status status, Party cancelledBy,
                                      LocalDateTime createdAt, LocalDateTime updatedAt, long version) {
        this.retryPolicy = retryPolicy == null ? RetryPolicy.NONE : retryPolicy;
        this.id = id;
        this.payerAccountId = payerAccountId;
        this.receiverAccountId = receiverAccountId;
        this.receiverPixKey = receiverPixKey;
        this.receiverName = receiverName;
        this.description = description;
        this.contractReference = contractReference;
        this.frequency = frequency;
        this.fixedAmount = fixedAmount;
        this.maxAmount = maxAmount;
        this.startDate = startDate;
        this.endDate = endDate;
        this.status = status;
        this.cancelledBy = cancelledBy;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    /** Pedido de quem recebe: fica PENDING até o pagador responder (no máximo até a data de início). */
    public static PixAutomaticAuthorization request(UUID payerAccountId, UUID receiverAccountId, String receiverPixKey,
                                                    String receiverName, String description, String contractReference,
                                                    Frequency frequency, Money fixedAmount, RetryPolicy retryPolicy,
                                                    LocalDate startDate, LocalDate endDate, LocalDate today,
                                                    LocalDateTime now) {
        if (payerAccountId.equals(receiverAccountId)) throw new BusinessRuleException("Pagador e recebedor são a mesma conta");
        if (description == null || description.isBlank()) throw new ValidationException("Descrição é obrigatória (o que está sendo cobrado)");
        if (frequency == null) throw new ValidationException("Frequência é obrigatória");
        if (fixedAmount != null && fixedAmount.isZero()) throw new ValidationException("Valor fixo deve ser maior que zero");
        if (startDate == null || !startDate.isAfter(today)) throw new DomainException("A data de início precisa ser futura");
        if (startDate.isAfter(today.plusDays(MAX_START_DAYS))) {
            throw new DomainException("A data de início pode ser em até " + MAX_START_DAYS + " dias");
        }
        if (endDate != null && endDate.isBefore(startDate)) throw new DomainException("A data de fim é anterior à de início");
        return new PixAutomaticAuthorization(UUID.randomUUID(), payerAccountId, receiverAccountId, receiverPixKey,
                receiverName, description.trim(), contractReference, frequency, fixedAmount, retryPolicy, null, startDate,
                endDate, Status.PENDING, null, now, now, 0);
    }

    public static PixAutomaticAuthorization reconstitute(UUID id, UUID payerAccountId, UUID receiverAccountId,
                                                         String receiverPixKey, String receiverName, String description,
                                                         String contractReference, Frequency frequency, Money fixedAmount,
                                                         RetryPolicy retryPolicy, Money maxAmount, LocalDate startDate,
                                                         LocalDate endDate, Status status, Party cancelledBy,
                                                         LocalDateTime createdAt, LocalDateTime updatedAt, long version) {
        return new PixAutomaticAuthorization(id, payerAccountId, receiverAccountId, receiverPixKey, receiverName,
                description, contractReference, frequency, fixedAmount, retryPolicy, maxAmount, startDate, endDate, status,
                cancelledBy, createdAt, updatedAt, version);
    }

    /**
     * Confere uma nova tentativa de uma cobrança recusada. Regra do arranjo: só se a autorização
     * previr; no máximo {@value #MAX_RETRIES}; uma por dia, sempre depois da anterior e a partir de
     * amanhã; até {@value #RETRY_WINDOW_DAYS} dias depois do vencimento.
     *
     * @param attemptsSoFar tentativas já feitas, contando a original
     */
    public void checkRetry(LocalDate dueDate, LocalDate lastAttemptDate, int attemptsSoFar, LocalDate retryDate,
                           LocalDate today) {
        if (retryPolicy == RetryPolicy.NONE) {
            throw new BusinessRuleException("Esta autorização não permite novas tentativas");
        }
        if (status != Status.ACTIVE) throw new BusinessRuleException("Autorização não está ativa");
        if (attemptsSoFar - 1 >= MAX_RETRIES) {
            throw new BusinessRuleException("As " + MAX_RETRIES + " novas tentativas permitidas já foram feitas");
        }
        if (retryDate == null || !retryDate.isAfter(today)) {
            throw new BusinessRuleException("A nova tentativa precisa ser a partir de amanhã");
        }
        if (!retryDate.isAfter(lastAttemptDate)) {
            throw new BusinessRuleException("Uma tentativa por dia, depois da anterior (" + lastAttemptDate + ")");
        }
        if (retryDate.isAfter(dueDate.plusDays(RETRY_WINDOW_DAYS))) {
            throw new BusinessRuleException("Novas tentativas só até " + RETRY_WINDOW_DAYS + " dias depois do vencimento ("
                    + dueDate.plusDays(RETRY_WINDOW_DAYS) + ")");
        }
    }

    /**
     * O pagador autoriza. No valor variável ele pode pôr um teto por cobrança; no fixo, o valor já é o
     * teto. Depois da data de início sem resposta, o pedido expirou.
     */
    public void approve(Money maxAmount, LocalDate today, LocalDateTime now) {
        requirePendingAnswerable(today);
        if (maxAmount != null) {
            if (fixedAmount != null) throw new ValidationException("Autorização de valor fixo não tem valor máximo");
            if (maxAmount.isZero()) throw new ValidationException("Valor máximo deve ser maior que zero");
        }
        this.maxAmount = maxAmount;
        this.status = Status.ACTIVE;
        this.updatedAt = now;
    }

    public void reject(LocalDate today, LocalDateTime now) {
        requirePendingAnswerable(today);
        this.status = Status.REJECTED;
        this.updatedAt = now;
    }

    /** Qualquer das partes encerra; as cobranças já agendadas são canceladas pelo use case. */
    public void cancel(Party by, LocalDateTime now) {
        if (status != Status.ACTIVE && status != Status.PENDING) {
            throw new BusinessRuleException("Autorização já está encerrada");
        }
        this.status = Status.CANCELLED;
        this.cancelledBy = by;
        this.updatedAt = now;
    }

    /**
     * Confere uma cobrança e devolve o ciclo dela. Recusa (422): autorização não ativa, vencimento
     * fora do período ou fora da janela de 2 a 10 dias, valor diferente do fixo ou acima do teto.
     */
    public int cycleForCharge(Money amount, LocalDate dueDate, LocalDate today) {
        if (displayStatus(today) != DisplayStatus.ACTIVE) throw new BusinessRuleException("Autorização não está ativa");
        if (amount == null || amount.isZero()) throw new ValidationException("Valor da cobrança deve ser maior que zero");
        if (dueDate == null) throw new ValidationException("Vencimento é obrigatório");
        long ahead = ChronoUnit.DAYS.between(today, dueDate);
        if (ahead < MIN_DAYS_AHEAD || ahead > MAX_DAYS_AHEAD) {
            throw new BusinessRuleException("A cobrança deve ser enviada de " + MIN_DAYS_AHEAD + " a " + MAX_DAYS_AHEAD
                    + " dias antes do vencimento");
        }
        if (dueDate.isBefore(startDate) || (endDate != null && dueDate.isAfter(endDate))) {
            throw new BusinessRuleException("Vencimento fora do período autorizado");
        }
        if (fixedAmount != null && !amount.equals(fixedAmount)) {
            throw new BusinessRuleException("Autorização é de valor fixo: " + fixedAmount.amount());
        }
        if (maxAmount != null && amount.isGreaterThan(maxAmount)) {
            throw new BusinessRuleException("Valor acima do máximo autorizado pelo pagador (" + maxAmount.amount() + ")");
        }
        return cycleOf(dueDate);
    }

    /** Ciclo (0, 1, 2...) em que a data cai. */
    public int cycleOf(LocalDate date) {
        if (date.isBefore(startDate)) throw new IllegalArgumentException("Data antes do início: " + date);
        int k = frequency == Frequency.WEEKLY
                ? (int) (ChronoUnit.DAYS.between(startDate, date) / 7)
                : (int) (ChronoUnit.MONTHS.between(startDate, date) / frequency.months);
        while (!frequency.cycleStart(startDate, k + 1).isAfter(date)) k++;
        while (frequency.cycleStart(startDate, k).isAfter(date)) k--;
        return k;
    }

    public enum DisplayStatus { PENDING, ACTIVE, REJECTED, CANCELLED, EXPIRED, FINISHED }

    /** Pedido sem resposta depois do início expirou; ativa depois do fim acabou. */
    public DisplayStatus displayStatus(LocalDate today) {
        return switch (status) {
            case PENDING -> today.isAfter(startDate) ? DisplayStatus.EXPIRED : DisplayStatus.PENDING;
            case ACTIVE -> endDate != null && today.isAfter(endDate) ? DisplayStatus.FINISHED : DisplayStatus.ACTIVE;
            case REJECTED -> DisplayStatus.REJECTED;
            case CANCELLED -> DisplayStatus.CANCELLED;
        };
    }

    private void requirePendingAnswerable(LocalDate today) {
        DisplayStatus shown = displayStatus(today);
        if (shown == DisplayStatus.EXPIRED) throw new BusinessRuleException("O pedido expirou: a data de início já passou");
        if (shown != DisplayStatus.PENDING) throw new BusinessRuleException("Pedido já foi respondido ou cancelado");
    }

    public UUID getId() { return id; }
    public UUID getPayerAccountId() { return payerAccountId; }
    public UUID getReceiverAccountId() { return receiverAccountId; }
    public String getReceiverPixKey() { return receiverPixKey; }
    public String getReceiverName() { return receiverName; }
    public String getDescription() { return description; }
    public String getContractReference() { return contractReference; }
    public Frequency getFrequency() { return frequency; }
    public Money getFixedAmount() { return fixedAmount; }
    public RetryPolicy getRetryPolicy() { return retryPolicy; }
    public Money getMaxAmount() { return maxAmount; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getEndDate() { return endDate; }
    public Status getStatus() { return status; }
    public Party getCancelledBy() { return cancelledBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
