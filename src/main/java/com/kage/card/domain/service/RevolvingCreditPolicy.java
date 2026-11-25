package com.kage.card.domain.service;

import com.kage.shared.domain.valueobject.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Regras do crédito rotativo, num lugar só (versão simplificada da regulação brasileira):
 *
 * - Pagamento mínimo: 15% do total, com piso de R$ 50 (ou o total, se for menor).
 * - Saldo não pago vai para a fatura seguinte com JUROS DO ROTATIVO (14% a.m.).
 * - Quem pagou menos que o mínimo está em atraso: além dos juros, MULTA de 2% (teto do CDC,
 *   art. 52 §1º) e JUROS DE MORA de 1% a.m. pro rata pelos dias de atraso.
 * - Teto (Lei 14.690/2023): juros + encargos do rotativo não podem passar de 100% da dívida original.
 *   Quem aplica o teto é o Card, que guarda principal e encargos já cobrados.
 */
public final class RevolvingCreditPolicy {

    public static final BigDecimal MIN_PAYMENT_RATE = new BigDecimal("0.15");
    public static final Money MIN_PAYMENT_FLOOR = Money.of("50.00");
    public static final BigDecimal REVOLVING_MONTHLY_RATE = new BigDecimal("0.14");
    public static final BigDecimal LATE_FINE_RATE = new BigDecimal("0.02");
    public static final BigDecimal LATE_INTEREST_MONTHLY_RATE = new BigDecimal("0.01");

    private RevolvingCreditPolicy() {
    }

    public static Money minimumPayment(Money total) {
        if (total.isZero()) return Money.ZERO;
        Money percentage = Money.of(total.amount().multiply(MIN_PAYMENT_RATE));
        Money minimum = percentage.isLessThan(MIN_PAYMENT_FLOOR) ? MIN_PAYMENT_FLOOR : percentage;
        return minimum.isGreaterThan(total) ? total : minimum;
    }

    /**
     * Encargos da virada, antes do teto.
     *
     * @param carried   saldo que não foi pago
     * @param paid      quanto foi pago da fatura
     * @param minimum   pagamento mínimo da fatura
     * @param dueDate   vencimento da fatura
     * @param rolledAt  data da virada (fechamento da fatura seguinte)
     */
    public static Charges chargesFor(Money carried, Money paid, Money minimum, LocalDate dueDate, LocalDate rolledAt) {
        Money interest = percent(carried, REVOLVING_MONTHLY_RATE);
        if (!paid.isLessThan(minimum)) {
            return new Charges(interest, Money.ZERO, Money.ZERO);
        }
        long lateDays = Math.max(0, ChronoUnit.DAYS.between(dueDate, rolledAt));
        Money fine = percent(carried, LATE_FINE_RATE);
        Money lateInterest = Money.of(carried.amount().multiply(LATE_INTEREST_MONTHLY_RATE)
                .multiply(BigDecimal.valueOf(lateDays)).divide(BigDecimal.valueOf(30), 2, RoundingMode.HALF_UP));
        return new Charges(interest, fine, lateInterest);
    }

    private static Money percent(Money base, BigDecimal rate) {
        return Money.of(base.amount().multiply(rate));
    }

    public record Charges(Money revolvingInterest, Money lateFine, Money lateInterest) {
        public Money total() {
            return revolvingInterest.add(lateFine).add(lateInterest);
        }

        public boolean late() {
            return !lateFine.isZero();
        }
    }
}
