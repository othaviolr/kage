package com.kage.savings.domain.entity;

import com.kage.shared.domain.DomainTime;
import com.kage.savings.domain.service.SavingsTaxPolicy;
import com.kage.savings.domain.valueobject.CdiSchedule;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.ValidationException;
import com.kage.shared.domain.valueobject.Money;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Caixinha: dinheiro separado da conta corrente, rendendo 100% do CDI por dia útil.
 *
 * O saldo é a soma dos {@link SavingsLot lotes} — um por depósito, cada um com a própria data, porque
 * IR e IOF dependem de quanto tempo cada real ficou aplicado. Cada lote acumula o rendimento
 * fracionário à parte e só os centavos inteiros vão para o saldo (arredondar todo dia faria quem
 * guarda pouco nunca render).
 *
 * Resgate: o valor pedido é BRUTO (sai da caixinha); o cliente recebe o líquido, depois do IOF e do
 * IR sobre o rendimento dos lotes consumidos, dos mais antigos para os mais novos.
 */
public class SavingsBox {

    public static final int MAX_NAME_LENGTH = 60;

    public enum Status { ACTIVE, CLOSED }

    /** O que um resgate tira, retém e entrega. */
    public record Withdrawal(Money gross, Money gain, Money iof, Money incomeTax, Money net) {}

    private final UUID id;
    private final UUID customerId;
    private final UUID accountId;
    private final String name;
    private final Money goalAmount;
    private final List<SavingsLot> lots;
    private Money totalYield;
    private Status status;
    private LocalDate lastYieldDate;
    private LocalDateTime goalReachedAt;
    private final LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime closedAt;
    private final Long version;

    private SavingsBox(UUID id, UUID customerId, UUID accountId, String name, Money goalAmount, List<SavingsLot> lots,
                       Money totalYield, Status status, LocalDate lastYieldDate, LocalDateTime goalReachedAt,
                       LocalDateTime createdAt, LocalDateTime updatedAt, LocalDateTime closedAt, Long version) {
        this.id = id;
        this.customerId = customerId;
        this.accountId = accountId;
        this.name = name;
        this.goalAmount = goalAmount;
        this.lots = new ArrayList<>(lots);
        this.lots.sort(Comparator.comparing(SavingsLot::getDepositedOn));
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
        LocalDateTime now = DomainTime.now();
        return new SavingsBox(UUID.randomUUID(), customerId, accountId, name.strip(), goalAmount, List.of(),
                Money.ZERO, Status.ACTIVE, today, null, now, now, null, null);
    }

    public static SavingsBox reconstitute(UUID id, UUID customerId, UUID accountId, String name, Money goalAmount,
                                          List<SavingsLot> lots, Money totalYield, Status status, LocalDate lastYieldDate,
                                          LocalDateTime goalReachedAt, LocalDateTime createdAt, LocalDateTime updatedAt,
                                          LocalDateTime closedAt, Long version) {
        return new SavingsBox(id, customerId, accountId, name, goalAmount, lots, totalYield, status, lastYieldDate,
                goalReachedAt, createdAt, updatedAt, closedAt, version);
    }

    /** Abre um lote novo. @return true se este depósito fez a caixinha atingir a meta (pela primeira vez) */
    public boolean deposit(Money amount, LocalDate today) {
        requireActive();
        requirePositive(amount);
        lots.add(SavingsLot.open(amount.amount(), today));
        this.updatedAt = DomainTime.now();
        return checkGoal();
    }

    /** O que o resgate de {@code gross} reteria hoje, sem resgatar. */
    public Withdrawal previewWithdrawal(Money gross, LocalDate today) {
        requirePositive(gross);
        if (gross.isGreaterThan(getBalance())) {
            throw new BusinessRuleException("Saldo da caixinha insuficiente (disponível: " + getBalance().amount() + ")");
        }
        return plan(gross.amount(), today, false);
    }

    /** Resgata {@code gross} da caixinha (FIFO pelos lotes). */
    public Withdrawal withdraw(Money gross, LocalDate today) {
        requireActive();
        Withdrawal withdrawal = previewWithdrawal(gross, today);
        plan(gross.amount(), today, true);
        lots.removeIf(SavingsLot::isEmpty);
        this.updatedAt = DomainTime.now();
        return withdrawal;
    }

    /** Valor líquido se resgatasse tudo hoje. */
    public Withdrawal previewFullWithdrawal(LocalDate today) {
        if (getBalance().isZero()) return new Withdrawal(Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO);
        return plan(getBalance().amount(), today, false);
    }

    private Withdrawal plan(BigDecimal gross, LocalDate today, boolean apply) {
        BigDecimal remaining = gross, gain = BigDecimal.ZERO, iof = BigDecimal.ZERO, incomeTax = BigDecimal.ZERO;
        for (SavingsLot lot : lots) {
            if (remaining.signum() == 0) break;
            BigDecimal take = remaining.min(lot.getBalance());
            BigDecimal lotGain = take.subtract(lot.principalFor(take)).max(BigDecimal.ZERO);
            SavingsTaxPolicy.Taxes taxes = SavingsTaxPolicy.on(lotGain, lot.daysHeld(today));
            gain = gain.add(lotGain);
            iof = iof.add(taxes.iof());
            incomeTax = incomeTax.add(taxes.incomeTax());
            if (apply) lot.consume(take);
            remaining = remaining.subtract(take);
        }
        BigDecimal net = gross.subtract(iof).subtract(incomeTax);
        return new Withdrawal(Money.of(gross), Money.of(gain), Money.of(iof), Money.of(incomeTax), Money.of(net));
    }

    /**
     * Rende os dias desde o último cálculo até onde o CDI é conhecido, capitalizando: os centavos de
     * cada dia já rendem no seguinte. Idempotente: {@code lastYieldDate} avança, e rodar de novo para
     * o mesmo período não rende nada.
     *
     * @return o que foi creditado no saldo
     */
    public Money accrueYield(CdiSchedule cdi) {
        LocalDate upTo = cdi.confirmedUntil();
        if (this.status != Status.ACTIVE || !upTo.isAfter(this.lastYieldDate)) return Money.ZERO;

        BigDecimal credited = BigDecimal.ZERO;
        for (LocalDate day = this.lastYieldDate.plusDays(1); !day.isAfter(upTo); day = day.plusDays(1)) {
            var rate = cdi.rateOn(day);
            if (rate.isEmpty()) continue; // fim de semana / feriado
            for (SavingsLot lot : lots) credited = credited.add(lot.accrue(day, rate.get()));
        }
        this.lastYieldDate = upTo;
        if (credited.signum() > 0) {
            this.totalYield = this.totalYield.add(Money.of(credited));
            this.updatedAt = DomainTime.now();
        }
        checkGoal();
        return Money.of(credited);
    }

    /** Encerra. O resgate do saldo (líquido de impostos) é feito antes, pelo use case. */
    public void close() {
        requireActive();
        if (!getBalance().isZero()) throw new BusinessRuleException("Resgate o saldo antes de encerrar a caixinha");
        this.status = Status.CLOSED;
        this.closedAt = DomainTime.now();
        this.updatedAt = this.closedAt;
    }

    private boolean checkGoal() {
        if (goalAmount == null || goalReachedAt != null || getBalance().isLessThan(goalAmount)) return false;
        this.goalReachedAt = DomainTime.now();
        return true;
    }

    private void requireActive() {
        if (this.status != Status.ACTIVE) throw new BusinessRuleException("Caixinha encerrada");
    }

    private static void requirePositive(Money amount) {
        if (amount == null || amount.isZero()) throw new ValidationException("Valor deve ser maior que zero");
    }

    public Money getBalance() {
        return Money.of(lots.stream().map(SavingsLot::getBalance).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    public UUID getId() { return id; }
    public UUID getCustomerId() { return customerId; }
    public UUID getAccountId() { return accountId; }
    public String getName() { return name; }
    public Money getGoalAmount() { return goalAmount; }
    public List<SavingsLot> getLots() { return List.copyOf(lots); }
    public Money getTotalYield() { return totalYield; }
    public Status getStatus() { return status; }
    public LocalDate getLastYieldDate() { return lastYieldDate; }
    public LocalDateTime getGoalReachedAt() { return goalReachedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public LocalDateTime getClosedAt() { return closedAt; }
    public Long getVersion() { return version; }
}
