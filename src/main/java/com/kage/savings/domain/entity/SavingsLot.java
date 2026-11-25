package com.kage.savings.domain.entity;

import com.kage.savings.domain.service.YieldPolicy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Um depósito na caixinha, rendendo por conta própria. Existe porque o imposto depende de quanto
 * tempo CADA real ficou aplicado: R$ 100 guardados há dois anos pagam 15% de IR sobre o rendimento;
 * R$ 100 guardados ontem, 22,5% (e IOF). O resgate consome os lotes mais antigos primeiro (FIFO).
 *
 * principal = quanto do que foi guardado ainda está aqui (base de custo); balance - principal = rendimento.
 */
public class SavingsLot {

    private final UUID id;
    private final LocalDate depositedOn;
    private BigDecimal principal;
    private BigDecimal balance;
    private BigDecimal pendingYield;

    private SavingsLot(UUID id, LocalDate depositedOn, BigDecimal principal, BigDecimal balance, BigDecimal pendingYield) {
        this.id = id;
        this.depositedOn = depositedOn;
        this.principal = principal;
        this.balance = balance;
        this.pendingYield = pendingYield;
    }

    static SavingsLot open(BigDecimal amount, LocalDate depositedOn) {
        return new SavingsLot(UUID.randomUUID(), depositedOn, amount, amount, BigDecimal.ZERO.setScale(YieldPolicy.PENDING_SCALE));
    }

    public static SavingsLot reconstitute(UUID id, LocalDate depositedOn, BigDecimal principal, BigDecimal balance,
                                          BigDecimal pendingYield) {
        return new SavingsLot(id, depositedOn, principal, balance, pendingYield);
    }

    /** Rende um dia. Lote depositado no próprio dia (ou depois) não rende nele. @return centavos creditados */
    BigDecimal accrue(LocalDate day, BigDecimal dailyRate) {
        if (!day.isAfter(depositedOn)) return BigDecimal.ZERO;
        pendingYield = pendingYield.add(balance.multiply(dailyRate)).setScale(YieldPolicy.PENDING_SCALE, RoundingMode.DOWN);
        BigDecimal cents = pendingYield.setScale(2, RoundingMode.DOWN);
        if (cents.signum() > 0) {
            balance = balance.add(cents);
            pendingYield = pendingYield.subtract(cents);
        }
        return cents;
    }

    /**
     * Quanto do principal sai junto com {@code take} (proporcional; o lote inteiro leva o principal inteiro).
     */
    BigDecimal principalFor(BigDecimal take) {
        if (take.compareTo(balance) >= 0) return principal;
        return principal.multiply(take).divide(balance, 2, RoundingMode.HALF_UP);
    }

    void consume(BigDecimal take) {
        BigDecimal principalOut = principalFor(take);
        principal = principal.subtract(principalOut);
        balance = balance.subtract(take);
    }

    public long daysHeld(LocalDate on) {
        return ChronoUnit.DAYS.between(depositedOn, on);
    }

    public BigDecimal gain() {
        return balance.subtract(principal).max(BigDecimal.ZERO);
    }

    public boolean isEmpty() {
        return balance.signum() == 0;
    }

    public UUID getId() { return id; }
    public LocalDate getDepositedOn() { return depositedOn; }
    public BigDecimal getPrincipal() { return principal; }
    public BigDecimal getBalance() { return balance; }
    public BigDecimal getPendingYield() { return pendingYield; }
}
