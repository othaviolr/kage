package com.kage.loan.domain.entity;

import com.kage.card.domain.service.IofPolicy;
import com.kage.loan.domain.service.LoanPolicy;
import com.kage.shared.domain.DomainTime;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.valueobject.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Empréstimo pessoal contratado: o que foi liberado, o IOF financiado, a taxa, o CET e as parcelas.
 * ACTIVE → PAID (todas pagas), CANCELLED (arrependimento em até 7 dias) ou RENEGOTIATED (o saldo virou
 * um acordo, outro Loan com origin RENEGOTIATION).
 */
public class Loan {

    public enum Status { ACTIVE, PAID, CANCELLED, RENEGOTIATED }

    /** NEW: empréstimo contratado, com dinheiro na conta. RENEGOTIATION: acordo sobre a dívida de outro. */
    public enum Origin { NEW, RENEGOTIATION }

    /**
     * @param paidAmount o que de fato saiu da conta (com desconto de antecipação ou encargos de atraso)
     */
    public record Installment(int number, LocalDate dueDate, Money amount, Money principal, Money interest,
                              boolean paid, LocalDateTime paidAt, Money paidAmount, Money lateCharges, Money discount) {

        Installment pay(Money paidAmount, Money lateCharges, Money discount, LocalDateTime at) {
            return new Installment(number, dueDate, amount, principal, interest, true, at, paidAmount, lateCharges, discount);
        }

        /** Quanto custa quitar esta parcela em {@code on}: com encargos se atrasada, com desconto se antecipada. */
        public Money costOn(LocalDate on, BigDecimal monthlyRate) {
            return on.isAfter(dueDate)
                    ? amount.add(LoanPolicy.lateCharges(amount, dueDate, on))
                    : LoanPolicy.presentValue(amount, monthlyRate, dueDate, on);
        }
    }

    private final UUID id;
    private final UUID accountId;
    private final UUID customerId;
    private final Money released;
    private final Money iof;
    private final Money financed;
    private final BigDecimal monthlyRate;
    private final int dueDay;
    private final BigDecimal cetMonthly;
    private final BigDecimal cetYearly;
    private Status status;
    private final LocalDate contractDate;
    private final LocalDateTime contractedAt;
    private LocalDateTime closedAt;
    private final List<Installment> installments;
    private final Origin origin;
    /** O empréstimo que este acordo renegociou; nulo em empréstimo novo. */
    private final UUID renegotiatedFrom;
    /** Entrada paga na renegociação; nula em empréstimo novo. */
    private final Money downPayment;
    private final Long version;

    private Loan(UUID id, UUID accountId, UUID customerId, Money released, Money iof, Money financed,
                 BigDecimal monthlyRate, int dueDay, BigDecimal cetMonthly, BigDecimal cetYearly, Status status,
                 LocalDate contractDate, LocalDateTime contractedAt, LocalDateTime closedAt, List<Installment> installments,
                 Origin origin, UUID renegotiatedFrom, Money downPayment, Long version) {
        this.id = id;
        this.accountId = accountId;
        this.customerId = customerId;
        this.released = released;
        this.iof = iof;
        this.financed = financed;
        this.monthlyRate = monthlyRate;
        this.dueDay = dueDay;
        this.cetMonthly = cetMonthly;
        this.cetYearly = cetYearly;
        this.status = status;
        this.contractDate = contractDate;
        this.contractedAt = contractedAt;
        this.closedAt = closedAt;
        this.installments = new ArrayList<>(installments);
        this.origin = origin;
        this.renegotiatedFrom = renegotiatedFrom;
        this.downPayment = downPayment;
        this.version = version;
    }

    public static Loan contract(UUID accountId, UUID customerId, int dueDay, LoanPolicy.Quote quote, LocalDate today) {
        return new Loan(UUID.randomUUID(), accountId, customerId, quote.released(), quote.iof(), quote.financed(),
                quote.monthlyRate(), dueDay, quote.cetMonthly(), quote.cetYearly(), Status.ACTIVE, today,
                DomainTime.now(), null, schedule(quote), Origin.NEW, null, null, null);
    }

    /**
     * O acordo que substitui {@code original}: {@code quote.released()} é a dívida renegociada (nada cai na
     * conta). Encerra o original como RENEGOTIATED.
     */
    public static Loan renegotiate(Loan original, int dueDay, LoanPolicy.Quote quote, Money downPayment, LocalDate today) {
        original.requireRenegotiable(today);
        original.close(Status.RENEGOTIATED);
        return new Loan(UUID.randomUUID(), original.accountId, original.customerId, quote.released(), quote.iof(),
                quote.financed(), quote.monthlyRate(), dueDay, quote.cetMonthly(), quote.cetYearly(), Status.ACTIVE, today,
                DomainTime.now(), null, schedule(quote), Origin.RENEGOTIATION, original.id, downPayment, null);
    }

    private static List<Installment> schedule(LoanPolicy.Quote quote) {
        return quote.schedule().stream()
                .map(i -> new Installment(i.number(), i.dueDate(), i.amount(), i.principal(), i.interest(), false,
                        null, null, null, null))
                .toList();
    }

    public static Loan reconstitute(UUID id, UUID accountId, UUID customerId, Money released, Money iof, Money financed,
                                    BigDecimal monthlyRate, int dueDay, BigDecimal cetMonthly, BigDecimal cetYearly,
                                    Status status, LocalDate contractDate, LocalDateTime contractedAt,
                                    LocalDateTime closedAt, List<Installment> installments, Origin origin,
                                    UUID renegotiatedFrom, Money downPayment, Long version) {
        return new Loan(id, accountId, customerId, released, iof, financed, monthlyRate, dueDay, cetMonthly, cetYearly,
                status, contractDate, contractedAt, closedAt, installments, origin, renegotiatedFrom, downPayment, version);
    }

    /** Há parcela vencida e não paga (venceu antes de {@code today}). */
    public boolean hasOverdue(LocalDate today) {
        return status == Status.ACTIVE && pending().stream().anyMatch(i -> today.isAfter(i.dueDate()));
    }

    /** Só empréstimo ativo com parcela em atraso se renegocia: em dia, o caminho é antecipar ou quitar. */
    public void requireRenegotiable(LocalDate today) {
        requireActive();
        if (!hasOverdue(today)) {
            throw new BusinessRuleException("Não há parcela em atraso: a renegociação é para dívida vencida "
                    + "(em dia, dá para antecipar parcelas ou quitar com desconto)");
        }
    }

    /** Principal ainda não pago: a base que já pagou IOF no contrato. */
    public Money unpaidPrincipal() {
        return pending().stream().map(Installment::principal).reduce(Money.ZERO, Money::add);
    }

    /** Dias de IOF já cobrados sobre o principal em aberto: média das parcelas pendentes, ponderada pelo principal. */
    public long iofCoveredDays() {
        BigDecimal weighted = BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;
        for (Installment i : pending()) {
            long days = Math.min(IofPolicy.MAX_DAYS, ChronoUnit.DAYS.between(contractDate, i.dueDate()));
            weighted = weighted.add(i.principal().amount().multiply(BigDecimal.valueOf(days)));
            total = total.add(i.principal().amount());
        }
        return total.signum() == 0 ? 0 : weighted.divide(total, 0, RoundingMode.DOWN).longValue();
    }

    /** A mais antiga em aberto (as parcelas são pagas em ordem). */
    public Optional<Installment> nextPending() {
        return installments.stream().filter(i -> !i.paid()).findFirst();
    }

    public List<Installment> pending() {
        return installments.stream().filter(i -> !i.paid()).toList();
    }

    /**
     * Paga a próxima parcela em {@code on}: em atraso, com multa e mora; antes do vencimento, com o
     * desconto dos juros.
     *
     * @return quanto sai da conta
     */
    public Money payNext(LocalDate on) {
        requireActive();
        Installment next = nextPending().orElseThrow(() -> new BusinessRuleException("Não há parcela em aberto"));
        Money cost = next.costOn(on, monthlyRate);
        Money late = on.isAfter(next.dueDate()) ? cost.subtract(next.amount()) : Money.ZERO;
        Money discount = on.isAfter(next.dueDate()) ? Money.ZERO : next.amount().subtract(cost);
        replace(next.pay(cost, late, discount, DomainTime.now()));
        if (nextPending().isEmpty()) close(Status.PAID);
        return cost;
    }

    /** Quanto custa quitar tudo em {@code on}. */
    public Money payoffCost(LocalDate on) {
        requireActive();
        return pending().stream().map(i -> i.costOn(on, monthlyRate)).reduce(Money.ZERO, Money::add);
    }

    /** Quita todas as parcelas em aberto. @return quanto sai da conta */
    public Money payOff(LocalDate on) {
        Money total = payoffCost(on);
        for (Installment i : pending()) {
            Money cost = i.costOn(on, monthlyRate);
            boolean late = on.isAfter(i.dueDate());
            replace(i.pay(cost, late ? cost.subtract(i.amount()) : Money.ZERO,
                    late ? Money.ZERO : i.amount().subtract(cost), DomainTime.now()));
        }
        close(Status.PAID);
        return total;
    }

    /**
     * Arrependimento (CDC, art. 49): em até 7 dias da contratação e sem parcela paga, o cliente devolve
     * o que recebeu — sem juros e sem IOF.
     */
    public Money withdraw(LocalDate on) {
        requireActive();
        if (origin == Origin.RENEGOTIATION) {
            throw new BusinessRuleException("Acordo de renegociação não tem arrependimento: não houve dinheiro novo");
        }
        if (ChronoUnit.DAYS.between(contractDate, on) > LoanPolicy.WITHDRAWAL_DAYS) {
            throw new BusinessRuleException("O prazo de arrependimento (7 dias) acabou: use a quitação antecipada");
        }
        if (installments.stream().anyMatch(Installment::paid)) {
            throw new BusinessRuleException("Já há parcela paga: use a quitação antecipada");
        }
        close(Status.CANCELLED);
        return released;
    }

    public boolean hasDueOn(LocalDate date) {
        return status == Status.ACTIVE && nextPending().map(i -> !i.dueDate().isAfter(date)).orElse(false);
    }

    private void replace(Installment updated) {
        installments.set(updated.number() - 1, updated);
    }

    private void close(Status newStatus) {
        this.status = newStatus;
        this.closedAt = DomainTime.now();
    }

    private void requireActive() {
        if (status == Status.PAID) throw new BusinessRuleException("Empréstimo já foi quitado");
        if (status == Status.CANCELLED) throw new BusinessRuleException("Empréstimo foi cancelado");
        if (status == Status.RENEGOTIATED) throw new BusinessRuleException("Empréstimo foi renegociado: o saldo está no acordo");
    }

    public UUID getId() { return id; }
    public UUID getAccountId() { return accountId; }
    public UUID getCustomerId() { return customerId; }
    public Money getReleased() { return released; }
    public Money getIof() { return iof; }
    public Money getFinanced() { return financed; }
    public BigDecimal getMonthlyRate() { return monthlyRate; }
    public int getDueDay() { return dueDay; }
    public BigDecimal getCetMonthly() { return cetMonthly; }
    public BigDecimal getCetYearly() { return cetYearly; }
    public Status getStatus() { return status; }
    public LocalDate getContractDate() { return contractDate; }
    public LocalDateTime getContractedAt() { return contractedAt; }
    public LocalDateTime getClosedAt() { return closedAt; }
    public List<Installment> getInstallments() { return Collections.unmodifiableList(installments); }
    public Origin getOrigin() { return origin; }
    public UUID getRenegotiatedFrom() { return renegotiatedFrom; }
    public Money getDownPayment() { return downPayment; }
    public Long getVersion() { return version; }
}
