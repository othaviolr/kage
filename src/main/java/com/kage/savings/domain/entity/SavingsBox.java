package com.kage.savings.domain.entity;

import com.kage.savings.domain.service.YieldPolicy;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.ValidationException;
import com.kage.shared.domain.valueobject.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Caixinha: dinheiro separado da conta corrente, rendendo 100% do CDI por dia útil.
 *
 * O rendimento acumula em {@code pendingYield} com {@link YieldPolicy#PENDING_SCALE} casas e só os
 * centavos inteiros vão para o saldo — arredondar todo dia faria quem guarda pouco nunca render
 * (R$ 10 rendem ~R$ 0,004 por dia).
 */
public class SavingsBox {

    public static final int MAX_NAME_LENGTH = 60;

    public enum Status { ACTIVE, CLOSED }

    private final UUID id;
    private final UUID customerId;
    private final UUID accountId;
    private String name;
    private final Money goalAmount;
    private Money balance;
    private BigDecimal pendingYield;
    private Money totalYield;
    private Status status;
    private LocalDate lastYieldDate;
    private LocalDateTime goalReachedAt;
    private final LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime closedAt;
    private final Long version;

    private SavingsBox(UUID id, UUID customerId, UUID accountId, String name, Money goalAmount, Money balance,
                       BigDecimal pendingYield, Money totalYield, Status status, LocalDate lastYieldDate,
                       LocalDateTime goalReachedAt, LocalDateTime createdAt, LocalDateTime updatedAt,
                       LocalDateTime closedAt, Long version) {
        this.id = id;
        this.customerId = customerId;
        this.accountId = accountId;
        this.name = name;
        this.goalAmount = goalAmount;
        this.balance = balance;
        this.pendingYield = pendingYield;
        this.totalYield = totalYield;
        this.status = status;
        this.lastYieldDate = lastYieldDate;
        this.goalReachedAt = goalReachedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.closedAt = closedAt;
        this.version = version;
    }

    /** Começa a render no próximo dia útil (o rendimento do dia da criação não conta). */
    public static SavingsBox create(UUID customerId, UUID accountId, String name, Money goalAmount, LocalDate today) {
        if (customerId == null || accountId == null) throw new ValidationException("Conta é obrigatória");
        if (name == null || name.isBlank()) throw new ValidationException("Dê um nome para a caixinha");
        if (name.strip().length() > MAX_NAME_LENGTH) throw new ValidationException("Nome com no máximo " + MAX_NAME_LENGTH + " caracteres");
        if (goalAmount != null && goalAmount.isZero()) throw new ValidationException("Meta deve ser maior que zero");
        LocalDateTime now = LocalDateTime.now();
        return new SavingsBox(UUID.randomUUID(), customerId, accountId, name.strip(), goalAmount, Money.ZERO,
                BigDecimal.ZERO.setScale(YieldPolicy.PENDING_SCALE), Money.ZERO, Status.ACTIVE, today, null, now, now, null, null);
    }

    public static SavingsBox reconstitute(UUID id, UUID customerId, UUID accountId, String name, Money goalAmount,
                                          Money balance, BigDecimal pendingYield, Money totalYield, Status status,
                                          LocalDate lastYieldDate, LocalDateTime goalReachedAt, LocalDateTime createdAt,
                                          LocalDateTime updatedAt, LocalDateTime closedAt, Long version) {
        return new SavingsBox(id, customerId, accountId, name, goalAmount, balance, pendingYield, totalYield, status,
                lastYieldDate, goalReachedAt, createdAt, updatedAt, closedAt, version);
    }

    /** @return true se este depósito fez a caixinha atingir a meta (pela primeira vez) */
    public boolean deposit(Money amount) {
        requireActive();
        requirePositive(amount);
        this.balance = this.balance.add(amount);
        this.updatedAt = LocalDateTime.now();
        return checkGoal();
    }

    public void withdraw(Money amount) {
        requireActive();
        requirePositive(amount);
        if (amount.isGreaterThan(this.balance)) {
            throw new BusinessRuleException("Saldo da caixinha insuficiente (disponível: " + this.balance.amount() + ")");
        }
        this.balance = this.balance.subtract(amount);
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Rende os dias úteis desde o último cálculo até {@code upTo} (inclusive), capitalizando: os
     * centavos de cada dia já rendem no seguinte. Idempotente: rodar de novo para a mesma data não
     * rende nada, porque {@code lastYieldDate} já avançou.
     *
     * @return o que foi creditado no saldo (centavos inteiros)
     */
    public Money accrueYield(LocalDate upTo, BigDecimal dailyRate) {
        if (this.status != Status.ACTIVE || !upTo.isAfter(this.lastYieldDate)) return Money.ZERO;

        Money credited = Money.ZERO;
        for (LocalDate day = this.lastYieldDate.plusDays(1); !day.isAfter(upTo); day = day.plusDays(1)) {
            if (!YieldPolicy.isBusinessDay(day)) continue;
            this.pendingYield = this.pendingYield.add(this.balance.amount().multiply(dailyRate))
                    .setScale(YieldPolicy.PENDING_SCALE, RoundingMode.DOWN);
            BigDecimal cents = this.pendingYield.setScale(2, RoundingMode.DOWN);
            if (cents.signum() > 0) {
                Money portion = Money.of(cents);
                this.balance = this.balance.add(portion);
                this.totalYield = this.totalYield.add(portion);
                this.pendingYield = this.pendingYield.subtract(cents);
                credited = credited.add(portion);
            }
        }
        this.lastYieldDate = upTo;
        if (!credited.isZero()) this.updatedAt = LocalDateTime.now();
        checkGoal();
        return credited;
    }

    /**
     * Encerra. Quem resgata o saldo (de volta para a conta) é o use case, antes; aqui só fecha. O
     * rendimento fracionário ainda pendente (menos de um centavo) é perdido, como em qualquer banco.
     */
    public Money close() {
        requireActive();
        Money remaining = this.balance;
        this.balance = Money.ZERO;
        this.status = Status.CLOSED;
        this.closedAt = LocalDateTime.now();
        this.updatedAt = this.closedAt;
        return remaining;
    }

    private boolean checkGoal() {
        if (goalAmount == null || goalReachedAt != null || balance.isLessThan(goalAmount)) return false;
        this.goalReachedAt = LocalDateTime.now();
        return true;
    }

    private void requireActive() {
        if (this.status != Status.ACTIVE) throw new BusinessRuleException("Caixinha encerrada");
    }

    private static void requirePositive(Money amount) {
        if (amount == null || amount.isZero()) throw new ValidationException("Valor deve ser maior que zero");
    }

    public UUID getId() { return id; }
    public UUID getCustomerId() { return customerId; }
    public UUID getAccountId() { return accountId; }
    public String getName() { return name; }
    public Money getGoalAmount() { return goalAmount; }
    public Money getBalance() { return balance; }
    public BigDecimal getPendingYield() { return pendingYield; }
    public Money getTotalYield() { return totalYield; }
    public Status getStatus() { return status; }
    public LocalDate getLastYieldDate() { return lastYieldDate; }
    public LocalDateTime getGoalReachedAt() { return goalReachedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public LocalDateTime getClosedAt() { return closedAt; }
    public Long getVersion() { return version; }
}
