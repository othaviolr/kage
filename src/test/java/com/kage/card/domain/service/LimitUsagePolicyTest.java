package com.kage.card.domain.service;

import com.kage.shared.domain.valueobject.Money;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LimitUsagePolicyTest {

    private static final Money LIMIT = Money.of("1000.00");

    @Test
    void percentual_arredondaParaBaixo() {
        assertThat(LimitUsagePolicy.usagePercent(Money.of("799.99"), LIMIT)).isEqualTo(79);
        assertThat(LimitUsagePolicy.usagePercent(Money.of("800.00"), LIMIT)).isEqualTo(80);
        assertThat(LimitUsagePolicy.usagePercent(Money.of("999.99"), LIMIT)).isEqualTo(99);
        assertThat(LimitUsagePolicy.usagePercent(Money.ZERO, LIMIT)).isZero();
    }

    @Test
    void faixas_abaixoDe80_nenhuma() {
        assertThat(LimitUsagePolicy.reached(Money.of("799.99"), LIMIT)).isEmpty();
    }

    @Test
    void faixas_entre80e100_soAPrimeira() {
        assertThat(LimitUsagePolicy.reached(Money.of("800.00"), LIMIT)).containsExactly(80);
        assertThat(LimitUsagePolicy.reached(Money.of("999.99"), LIMIT)).containsExactly(80);
    }

    @Test
    void faixas_limiteTodoEmUso_ouAlem_asDuas() {
        assertThat(LimitUsagePolicy.reached(Money.of("1000.00"), LIMIT)).containsExactly(80, 100);
        // encargos do rotativo podem levar a dívida além do limite
        assertThat(LimitUsagePolicy.reached(Money.of("1080.00"), LIMIT)).containsExactly(80, 100);
        assertThat(LimitUsagePolicy.usagePercent(Money.of("1080.00"), LIMIT)).isEqualTo(108);
    }
}
