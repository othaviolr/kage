package com.kage.boleto.domain.service;

import com.kage.shared.domain.service.BusinessCalendar;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Encargos de boleto pago com atraso.
 *
 * <ul>
 *   <li>Vencimento em fim de semana ou feriado pode ser pago no próximo dia útil sem encargos
 *       (regra da FEBRABAN/Bacen).</li>
 *   <li><b>Multa</b>: percentual único sobre o valor, no máximo 2% (Código de Defesa do Consumidor,
 *       art. 52 §1º).</li>
 *   <li><b>Juros de mora</b>: percentual ao mês, pro rata por dia corrido de atraso contado do
 *       vencimento original, no máximo 1% a.m. (Código Civil, art. 406, e o padrão de mercado).</li>
 * </ul>
 */
public final class BoletoChargePolicy {

    public static final BigDecimal MAX_FINE_PERCENT = new BigDecimal("2.00");
    public static final BigDecimal MAX_MONTHLY_INTEREST_PERCENT = new BigDecimal("1.00");

    public record Charges(long daysLate, BigDecimal fine, BigDecimal interest) {
        public BigDecimal total() {
            return fine.add(interest);
        }
    }

    private BoletoChargePolicy() {
    }

    /** Último dia para pagar sem encargos: o vencimento, ou o próximo dia útil se ele não for útil. */
    public static LocalDate graceDate(LocalDate dueDate) {
        LocalDate d = dueDate;
        while (!BusinessCalendar.isBusinessDay(d)) d = d.plusDays(1);
        return d;
    }

    public static Charges on(BigDecimal amount, LocalDate dueDate, LocalDate paymentDate,
                             BigDecimal finePercent, BigDecimal monthlyInterestPercent) {
        if (!paymentDate.isAfter(graceDate(dueDate))) {
            return new Charges(0, BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2));
        }
        long daysLate = ChronoUnit.DAYS.between(dueDate, paymentDate);
        BigDecimal fine = amount.multiply(finePercent).movePointLeft(2).setScale(2, RoundingMode.HALF_UP);
        BigDecimal interest = amount.multiply(monthlyInterestPercent).movePointLeft(2)
                .multiply(BigDecimal.valueOf(daysLate)).divide(BigDecimal.valueOf(30), 2, RoundingMode.HALF_UP);
        return new Charges(daysLate, fine, interest);
    }
}
