package com.kage.card.domain.service;

import com.kage.shared.domain.exception.ValidationException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InternationalPurchasePolicyTest {

    private static ExchangeRateProvider.Rate ptax(String currency, String rate) {
        return new ExchangeRateProvider.Rate(currency, new BigDecimal(rate), LocalDate.of(2034, 1, 5), "teste");
    }

    @Test
    void dolar_ptaxMaisSpread_eIofSobreOConvertido() {
        InternationalPurchasePolicy.Quote q = InternationalPurchasePolicy.quote("USD", new BigDecimal("100"), ptax("USD", "5.50"));
        assertThat(q.rate()).isEqualByComparingTo("5.7200");  // 5,50 × 1,04
        assertThat(q.brl()).isEqualByComparingTo("572.00");
        assertThat(q.iof()).isEqualByComparingTo("20.02");    // 3,5% de 572,00
        assertThat(q.total()).isEqualByComparingTo("592.02");
        assertThat(q.ptaxDate()).isEqualTo(LocalDate.of(2034, 1, 5));
    }

    @Test
    void euro_arredondaNoCentavo() {
        InternationalPurchasePolicy.Quote q = InternationalPurchasePolicy.quote("EUR", new BigDecimal("49.99"), ptax("EUR", "6.1234"));
        assertThat(q.rate()).isEqualByComparingTo("6.3683");  // 6,1234 × 1,04 = 6,368336
        assertThat(q.brl()).isEqualByComparingTo("318.35");   // 49,99 × 6,3683 = 318,351317
        assertThat(q.iof()).isEqualByComparingTo("11.14");    // 318,35 × 0,035 = 11,14225
    }

    @Test
    void emReaisNumEstabelecimentoDeFora_soOIof() {
        InternationalPurchasePolicy.Quote q = InternationalPurchasePolicy.inReais(new BigDecimal("200"), LocalDate.of(2034, 1, 5));
        assertThat(q.currency()).isEqualTo("BRL");
        assertThat(q.brl()).isEqualByComparingTo("200.00");
        assertThat(q.iof()).isEqualByComparingTo("7.00");
        assertThat(q.total()).isEqualByComparingTo("207.00");
    }

    @Test
    void moedas() {
        assertThat(InternationalPurchasePolicy.currencyOf(null)).isNull();
        assertThat(InternationalPurchasePolicy.currencyOf("brl")).isNull();
        assertThat(InternationalPurchasePolicy.currencyOf(" usd ")).isEqualTo("USD");
        assertThatThrownBy(() -> InternationalPurchasePolicy.currencyOf("JPY")).isInstanceOf(ValidationException.class);
    }
}
