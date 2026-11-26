package com.kage.card.domain.service;

import com.kage.shared.domain.valueobject.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * IOF das operações de crédito de pessoa física (Decreto 6.306/2007, art. 7º): alíquota adicional de
 * 0,38% sobre o valor da operação, mais 0,0082% ao dia sobre o principal financiado, contando no
 * máximo 365 dias. Imposto: o banco cobra e repassa — não entra no teto de juros e encargos da Lei
 * 14.690/2023.
 */
public final class IofPolicy {

    public static final BigDecimal ADDITIONAL_RATE = new BigDecimal("0.0038");
    public static final BigDecimal DAILY_RATE = new BigDecimal("0.000082");
    public static final int MAX_DAYS = 365;

    private IofPolicy() {
    }

    /** Rotativo: sobre o saldo levado, pelos dias até ele vencer de novo. */
    public static Money revolving(Money balance, long days) {
        BigDecimal value = balance.amount().multiply(ADDITIONAL_RATE)
                .add(balance.amount().multiply(DAILY_RATE).multiply(BigDecimal.valueOf(capped(days))));
        return Money.of(value.setScale(2, RoundingMode.HALF_UP));
    }

    /**
     * Parcelamento: adicional sobre o principal, e a parte diária sobre o que cada parcela amortiza,
     * pelos dias até o vencimento dela.
     *
     * @param amortizations quanto do principal cada parcela paga (soma = principal)
     * @param days          dias até o vencimento de cada parcela
     */
    public static Money installments(Money principal, List<Money> amortizations, List<Long> days) {
        if (amortizations.size() != days.size()) throw new IllegalArgumentException("Uma data por parcela");
        BigDecimal value = principal.amount().multiply(ADDITIONAL_RATE);
        for (int k = 0; k < amortizations.size(); k++) {
            value = value.add(amortizations.get(k).amount().multiply(DAILY_RATE).multiply(BigDecimal.valueOf(capped(days.get(k)))));
        }
        return Money.of(value.setScale(2, RoundingMode.HALF_UP));
    }

    private static long capped(long days) {
        return Math.max(0, Math.min(days, MAX_DAYS));
    }
}
