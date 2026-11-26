package com.kage.loan.domain.service;

import com.kage.card.domain.service.CetPolicy;
import com.kage.card.domain.service.IofPolicy;
import com.kage.shared.domain.exception.ValidationException;
import com.kage.shared.domain.valueobject.Money;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Regras do empréstimo pessoal: Tabela Price a {@link #MONTHLY_RATE}, IOF financiado (calculado sobre o
 * valor liberado e somado ao financiado, então o cliente recebe o que pediu), CET sobre o liberado,
 * parcela de no máximo {@link #MAX_COMMITMENT} da renda, encargos de atraso e desconto de juros na
 * antecipação (CDC, art. 52, §2º).
 */
public final class LoanPolicy {

    public static final BigDecimal MONTHLY_RATE = new BigDecimal("0.0399");
    public static final Money MIN_AMOUNT = Money.of("200.00");
    public static final Money MAX_AMOUNT = Money.of("50000.00");
    public static final int MIN_INSTALLMENTS = 3;
    public static final int MAX_INSTALLMENTS = 24;
    /** A parcela não passa de 30% da renda mensal (estimada pela movimentação da conta). */
    public static final BigDecimal MAX_COMMITMENT = new BigDecimal("0.30");
    /** A 1ª parcela vence pelo menos 30 dias depois da contratação. */
    public static final int MIN_FIRST_DUE_DAYS = 30;
    public static final BigDecimal LATE_FINE_RATE = new BigDecimal("0.02");
    public static final BigDecimal LATE_INTEREST_MONTHLY_RATE = new BigDecimal("0.01");
    /** Direito de arrependimento (CDC, art. 49): contratação fora do estabelecimento. */
    public static final int WITHDRAWAL_DAYS = 7;

    private static final MathContext MC = MathContext.DECIMAL64;

    private LoanPolicy() {
    }

    /** Uma parcela do cronograma: quanto paga, quanto disso é principal e quanto é juros. */
    public record Installment(int number, LocalDate dueDate, Money amount, Money principal, Money interest) {}

    /**
     * @param released     o que cai na conta
     * @param financed     released + IOF (o que vai para as parcelas)
     * @param cetMonthly   e cetYearly: frações (0,05 = 5%)
     */
    public record Quote(Money released, Money iof, Money financed, int installments, BigDecimal monthlyRate,
                        Money installmentAmount, Money totalPaid, Money totalInterest, BigDecimal cetMonthly,
                        BigDecimal cetYearly, List<Installment> schedule) {}

    public static Quote quote(Money released, int installments, int dueDay, LocalDate contractDate) {
        validate(released, installments, dueDay);
        List<LocalDate> dueDates = dueDates(contractDate, dueDay, installments);
        List<Long> days = CetPolicy.days(contractDate, dueDates);

        // IOF sobre o liberado, com a amortização que ele teria; depois ele é financiado junto
        List<Installment> base = price(released, installments, dueDates, MONTHLY_RATE);
        Money iof = IofPolicy.installments(released, base.stream().map(Installment::principal).toList(), days);
        Money financed = released.add(iof);
        List<Installment> schedule = price(financed, installments, dueDates, MONTHLY_RATE);

        Money total = schedule.stream().map(Installment::amount).reduce(Money.ZERO, Money::add);
        CetPolicy.Cet cet = CetPolicy.of(released, contractDate,
                schedule.stream().map(i -> new CetPolicy.Flow(i.dueDate(), i.amount())).toList());
        return new Quote(released, iof, financed, installments, MONTHLY_RATE, schedule.get(0).amount(), total,
                total.subtract(financed), cet.monthly(), cet.yearly(), schedule);
    }

    /** Price: parcela fixa; a última acerta os centavos para o principal fechar. */
    static List<Installment> price(Money principal, int n, List<LocalDate> dueDates, BigDecimal monthlyRate) {
        BigDecimal i = monthlyRate;
        BigDecimal factor = BigDecimal.ONE.add(i).pow(n, MC);
        BigDecimal pmt = principal.amount().multiply(i).multiply(factor)
                .divide(factor.subtract(BigDecimal.ONE), 2, RoundingMode.HALF_UP);
        BigDecimal balance = principal.amount();
        List<Installment> result = new ArrayList<>(n);
        for (int k = 1; k <= n; k++) {
            BigDecimal interest = balance.multiply(i).setScale(2, RoundingMode.HALF_UP);
            BigDecimal amortization = k == n ? balance : pmt.subtract(interest);
            result.add(new Installment(k, dueDates.get(k - 1), Money.of(amortization.add(interest)),
                    Money.of(amortization), Money.of(interest)));
            balance = balance.subtract(amortization);
        }
        return result;
    }

    /**
     * Acordo de renegociação: {@code debt} (o que se deve hoje, já sem a entrada) em parcelas Price à taxa
     * do contrato original — sem dinheiro novo, sem juros maiores. O IOF é só o complementar (Decreto
     * 6.306/2007, art. 7º, §7º): a parte diária sobre o principal que o contrato original já tributou,
     * pelos dias que o acordo estende além do que já foi cobrado, até 365 dias contados da operação
     * original; a alíquota adicional de 0,38% não é cobrada de novo.
     *
     * @param taxedPrincipal principal ainda não pago do contrato original (a base que já pagou IOF)
     * @param coveredDays    dias de IOF já cobrados sobre esse principal (média ponderada das parcelas)
     */
    public static Quote renegotiation(Money debt, int installments, int dueDay, LocalDate today, BigDecimal monthlyRate,
                                      LocalDate originalContractDate, Money taxedPrincipal, long coveredDays) {
        if (installments < MIN_INSTALLMENTS || installments > MAX_INSTALLMENTS) {
            throw new ValidationException("Parcelas: de " + MIN_INSTALLMENTS + " a " + MAX_INSTALLMENTS);
        }
        if (dueDay < 1 || dueDay > 28) throw new ValidationException("Dia de vencimento: de 1 a 28");
        if (!debt.isGreaterThan(Money.ZERO)) throw new ValidationException("Não há saldo para renegociar");
        List<LocalDate> dueDates = dueDates(today, dueDay, installments);

        List<Installment> base = price(debt, installments, dueDates, monthlyRate);
        BigDecimal taxedShare = taxedPrincipal.amount().compareTo(debt.amount()) >= 0 ? BigDecimal.ONE
                : taxedPrincipal.amount().divide(debt.amount(), MC);
        BigDecimal iofValue = BigDecimal.ZERO;
        for (Installment k : base) {
            long total = Math.min(IofPolicy.MAX_DAYS, ChronoUnit.DAYS.between(originalContractDate, k.dueDate()));
            long extra = Math.max(0, total - coveredDays);
            iofValue = iofValue.add(k.principal().amount().multiply(taxedShare).multiply(IofPolicy.DAILY_RATE)
                    .multiply(BigDecimal.valueOf(extra)));
        }
        Money iof = Money.of(iofValue.setScale(2, RoundingMode.HALF_UP));
        Money financed = debt.add(iof);
        List<Installment> schedule = price(financed, installments, dueDates, monthlyRate);

        Money total = schedule.stream().map(Installment::amount).reduce(Money.ZERO, Money::add);
        CetPolicy.Cet cet = CetPolicy.of(debt, today,
                schedule.stream().map(i -> new CetPolicy.Flow(i.dueDate(), i.amount())).toList());
        return new Quote(debt, iof, financed, installments, monthlyRate, schedule.get(0).amount(), total,
                total.subtract(financed), cet.monthly(), cet.yearly(), schedule);
    }

    /** 1ª parcela no primeiro dia {@code dueDay} a pelo menos 30 dias da contratação; depois, mês a mês. */
    public static List<LocalDate> dueDates(LocalDate contractDate, int dueDay, int installments) {
        LocalDate first = contractDate.withDayOfMonth(dueDay);
        while (ChronoUnit.DAYS.between(contractDate, first) < MIN_FIRST_DUE_DAYS) first = first.plusMonths(1);
        List<LocalDate> result = new ArrayList<>(installments);
        for (int k = 0; k < installments; k++) result.add(first.plusMonths(k));
        return result;
    }

    /** Maior parcela que a renda comporta. */
    public static Money maxInstallment(Money monthlyIncome) {
        return Money.of(monthlyIncome.amount().multiply(MAX_COMMITMENT).setScale(2, RoundingMode.DOWN));
    }

    /**
     * Maior valor liberado cuja parcela (com o IOF financiado) cabe em {@code maxInstallment} em
     * {@code installments}x, até o teto do produto.
     */
    public static Money maxAmount(Money maxInstallment, int installments, int dueDay, LocalDate contractDate) {
        if (maxInstallment.isZero()) return Money.ZERO;
        BigDecimal low = BigDecimal.ZERO;
        BigDecimal high = MAX_AMOUNT.amount();
        if (!quote(MAX_AMOUNT, installments, dueDay, contractDate).installmentAmount().isGreaterThan(maxInstallment)) {
            return MAX_AMOUNT;
        }
        while (high.subtract(low).compareTo(new BigDecimal("0.01")) > 0) {
            BigDecimal mid = low.add(high).divide(BigDecimal.valueOf(2), 2, RoundingMode.DOWN);
            boolean fits = mid.compareTo(MIN_AMOUNT.amount()) < 0
                    || !quote(Money.of(mid), installments, dueDay, contractDate).installmentAmount().isGreaterThan(maxInstallment);
            if (fits) low = mid;
            else high = mid;
        }
        Money result = Money.of(low.setScale(0, RoundingMode.DOWN)); // oferta em reais inteiros
        return result.isLessThan(MIN_AMOUNT) ? Money.ZERO : result;
    }

    /** Multa de 2% + mora de 1% ao mês pro rata, do vencimento até {@code paidOn}. Zero se em dia. */
    public static Money lateCharges(Money amount, LocalDate dueDate, LocalDate paidOn) {
        long days = ChronoUnit.DAYS.between(dueDate, paidOn);
        if (days <= 0) return Money.ZERO;
        BigDecimal fine = amount.amount().multiply(LATE_FINE_RATE).setScale(2, RoundingMode.HALF_UP);
        BigDecimal interest = amount.amount().multiply(LATE_INTEREST_MONTHLY_RATE).multiply(BigDecimal.valueOf(days))
                .divide(BigDecimal.valueOf(30), 2, RoundingMode.HALF_UP);
        return Money.of(fine.add(interest));
    }

    /** Parcela paga antes do vencimento: a valor presente pela taxa do contrato (CDC, art. 52, §2º). */
    public static Money presentValue(Money amount, BigDecimal monthlyRate, LocalDate dueDate, LocalDate paidOn) {
        long days = ChronoUnit.DAYS.between(paidOn, dueDate);
        if (days <= 0) return amount;
        double factor = Math.pow(1 + monthlyRate.doubleValue(), days / 30.0);
        return Money.of(amount.amount().divide(BigDecimal.valueOf(factor), 2, RoundingMode.HALF_UP));
    }

    private static void validate(Money amount, int installments, int dueDay) {
        if (amount.isLessThan(MIN_AMOUNT) || amount.isGreaterThan(MAX_AMOUNT)) {
            throw new ValidationException("Valor do empréstimo: de R$ 200,00 a R$ 50.000,00");
        }
        if (installments < MIN_INSTALLMENTS || installments > MAX_INSTALLMENTS) {
            throw new ValidationException("Parcelas: de " + MIN_INSTALLMENTS + " a " + MAX_INSTALLMENTS);
        }
        if (dueDay < 1 || dueDay > 28) throw new ValidationException("Dia de vencimento: de 1 a 28");
    }
}
