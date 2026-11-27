package com.kage.card.domain.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class BillingDayPolicyTest {

    @ParameterizedTest(name = "vence dia {0} → fecha dia {1}")
    @CsvSource({
            "20, 10", // o padrão do Kage
            "28, 18",
            "11, 1",
            "5, 25",  // fecha no mês anterior ao do vencimento
            "1, 21",
            "8, 28",
            "9, 28",  // 29 não existe em todo mês: fecha dia 28 (11 dias antes)
            "10, 28"  // 30 também não: dia 28 (12 dias antes)
    })
    void fechamentoDezDiasAntes_semPassarDo28(int dueDay, int closingDay) {
        assertThat(BillingDayPolicy.closingDayFor(dueDay)).isEqualTo(closingDay);
    }
}
