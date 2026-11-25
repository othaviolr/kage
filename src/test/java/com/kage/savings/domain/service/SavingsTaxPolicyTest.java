package com.kage.savings.domain.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class SavingsTaxPolicyTest {

    @ParameterizedTest
    @CsvSource({"1, 0.96", "2, 0.93", "10, 0.66", "15, 0.50", "29, 0.03", "30, 0", "365, 0"})
    void iofIsRegressiveDuringTheFirst30Days(long days, String rate) {
        assertThat(SavingsTaxPolicy.iofRate(days)).isEqualByComparingTo(rate);
    }

    @ParameterizedTest
    @CsvSource({"1, 0.225", "180, 0.225", "181, 0.20", "360, 0.20", "361, 0.175", "720, 0.175", "721, 0.15"})
    void incomeTaxTable(long days, String rate) {
        assertThat(SavingsTaxPolicy.incomeTaxRate(days)).isEqualByComparingTo(rate);
    }

    @Test
    void incomeTaxIsChargedOnTheGainAfterIof() {
        // R$ 10 de rendimento em 10 dias: IOF 66% = 6,60; IR 22,5% de 3,40 = 0,765 -> 0,76
        var taxes = SavingsTaxPolicy.on(new BigDecimal("10.00"), 10);
        assertThat(taxes.iof()).isEqualByComparingTo("6.60");
        assertThat(taxes.incomeTax()).isEqualByComparingTo("0.76");
    }

    @Test
    void noGainNoTax() {
        var taxes = SavingsTaxPolicy.on(BigDecimal.ZERO, 3);
        assertThat(taxes.total()).isEqualByComparingTo("0");
    }
}
