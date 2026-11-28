package com.kage.notification.application.usecase;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class PixChargePaidTemplateTest {

    @Test
    void comNomeEMensagem() {
        NotificationTemplates.Text t = NotificationTemplates.pixChargePaid("Ana Souza", new BigDecimal("80.00"), "Pizza de sexta");
        assertThat(t.title()).isEqualTo("Cobrança paga");
        assertThat(t.message()).isEqualTo("Ana Souza pagou R$ 80,00 de sua cobrança \"Pizza de sexta\".");
    }

    @Test
    void semMensagem_eSemNome() {
        assertThat(NotificationTemplates.pixChargePaid(null, new BigDecimal("1234.50"), "  ").message())
                .isEqualTo("Alguém pagou R$ 1.234,50 de sua cobrança PIX.");
    }
}
