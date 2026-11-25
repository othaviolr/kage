package com.kage.card.domain.service;

import com.kage.shared.domain.valueobject.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class AnticipationPolicyTest {

    private static final BigDecimal RATE = InstallmentPolicy.INVOICE_MONTHLY_RATE;

    @Test
    void valorPresente_descontaOsJurosPelosMesesQueFaltam() {
        // 100 / 1,0899^3 = 77,2396...
        assertThat(AnticipationPolicy.presentValue(Money.of("100.00"), RATE, 3)).isEqualTo(Money.of("77.24"));
        // parcela de 222,85 (1000 em 6x): um mês antes = 204,47; cinco meses antes = 144,90
        assertThat(AnticipationPolicy.presentValue(Money.of("222.85"), RATE, 1)).isEqualTo(Money.of("204.47"));
        assertThat(AnticipationPolicy.presentValue(Money.of("222.85"), RATE, 5)).isEqualTo(Money.of("144.90"));
    }

    @Test
    void semJurosOuSemMeses_naoHaDesconto() {
        assertThat(AnticipationPolicy.presentValue(Money.of("100.00"), BigDecimal.ZERO, 6)).isEqualTo(Money.of("100.00"));
        assertThat(AnticipationPolicy.presentValue(Money.of("100.00"), RATE, 0)).isEqualTo(Money.of("100.00"));
    }

    @Test
    void taxaImplicita_recuperaATaxaDaTabelaPrice() {
        // 1000 em 6x a 8,99% = 222,85 (centavos arredondados): a taxa recalculada fica a menos de 0,001%
        BigDecimal rate = AnticipationPolicy.impliedMonthlyRate(Money.of("1000.00"), Money.of("222.85"), 6);
        assertThat(rate.doubleValue()).isCloseTo(0.0899, within(0.00001));
    }

    @Test
    void taxaImplicita_respeitaOTetoLegal() {
        // teto cortou os juros: 1000 viraram 6x de 200 (1200), não 6x de 222,85 -> taxa efetiva de ~5,47%
        BigDecimal rate = AnticipationPolicy.impliedMonthlyRate(Money.of("1000.00"), Money.of("200.00"), 6);
        assertThat(rate.doubleValue()).isCloseTo(0.054718, within(0.000001));
    }

    @Test
    void taxaImplicita_semJurosEZero() {
        assertThat(AnticipationPolicy.impliedMonthlyRate(Money.of("1200.00"), Money.of("100.00"), 12)).isZero();
    }
}
