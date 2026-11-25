package com.kage.card.domain.service;

import com.kage.shared.domain.valueobject.Money;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Antecipação de parcelas: pagar hoje parcelas que venceriam em faturas futuras.
 *
 * CDC, art. 52, §2º: na liquidação antecipada, total ou parcial, o consumidor tem direito à redução
 * proporcional dos juros. Cada parcela é trazida a valor presente pela taxa do próprio contrato,
 * pelos meses que faltam até a fatura dela — quanto mais distante, maior o desconto.
 *
 * Compra parcelada sem juros não tem juros embutidos: antecipa pelo valor de face.
 */
public final class AnticipationPolicy {

    private static final MathContext MC = MathContext.DECIMAL64;

    private AnticipationPolicy() {
    }

    /** Valor presente de uma parcela que venceria daqui a {@code months} faturas. */
    public static Money presentValue(Money installment, BigDecimal monthlyRate, int months) {
        if (monthlyRate.signum() == 0 || months <= 0) return installment;
        BigDecimal factor = BigDecimal.ONE.add(monthlyRate).pow(months, MC);
        return Money.of(installment.amount().divide(factor, 2, RoundingMode.HALF_UP));
    }

    /**
     * Taxa mensal implícita de um parcelamento de {@code count} parcelas de {@code installment} sobre
     * {@code principal}: a {@code r} tal que principal = parcela × (1 − (1 + r)^−n) / r.
     *
     * Recalcular (em vez de assumir os 8,99% da tabela) respeita o caso em que o teto da Lei
     * 14.690/2023 cortou os juros do plano: aí a taxa efetiva é menor, e o desconto também — nunca
     * se devolve mais juros do que foi cobrado. Sem juros (parcelas somando o principal ou menos),
     * a taxa é zero.
     */
    public static BigDecimal impliedMonthlyRate(Money principal, Money installment, int count) {
        BigDecimal p = principal.amount();
        BigDecimal a = installment.amount();
        if (a.multiply(BigDecimal.valueOf(count)).compareTo(p) <= 0) return BigDecimal.ZERO;

        // valor presente cai com a taxa: bissecção em [0, 100% a.m.]
        BigDecimal low = BigDecimal.ZERO;
        BigDecimal high = BigDecimal.ONE;
        for (int iteration = 0; iteration < 60; iteration++) {
            BigDecimal mid = low.add(high).divide(BigDecimal.valueOf(2), MC);
            if (annuityPresentValue(a, mid, count).compareTo(p) > 0) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return low.add(high).divide(BigDecimal.valueOf(2), MC).setScale(6, RoundingMode.HALF_UP);
    }

    private static BigDecimal annuityPresentValue(BigDecimal installment, BigDecimal rate, int count) {
        BigDecimal factor = BigDecimal.ONE.add(rate).pow(count, MC);
        BigDecimal discount = BigDecimal.ONE.subtract(BigDecimal.ONE.divide(factor, MC), MC);
        return installment.multiply(discount, MC).divide(rate, MC);
    }
}
