package com.kage.card.domain.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PixBrCodeTest {

    @Test
    void exemploDoManualDoBancoCentral() {
        // Manual de Padrões para Iniciação do PIX (BCB): chave aleatória, sem valor, sem txid
        assertThat(PixBrCode.of("123e4567-e12b-12d1-a456-426655440000", null, "Fulano de Tal", "BRASILIA", null, false))
                .isEqualTo("00020126580014br.gov.bcb.pix0136123e4567-e12b-12d1-a456-426655440000"
                        + "5204000053039865802BR5913Fulano de Tal6008BRASILIA62070503***63041D3D");
    }

    @Test
    void comValor_txid_eUsoUnico() {
        String code = PixBrCode.of("5b1e0c6a-2f4d-4c8e-9a7b-3d2f1e0c9b8a", new BigDecimal("1000.00"), "KAGE", "SAO PAULO",
                "FATABC123", true);
        assertThat(code).startsWith("000201010212") // versão 01, uso único
                .contains("54071000.00")
                .contains("5802BR5904KAGE6009SAO PAULO")
                .contains("62130509FATABC123")
                .matches(".*6304[0-9A-F]{4}$");
        // o CRC é de tudo o que vem antes, inclusive o "6304"
        assertThat(code.substring(code.length() - 4)).isEqualTo(PixBrCode.crc16(code.substring(0, code.length() - 4)));
    }

    @Test
    void nomeEcidadeSemAcento_eNoTamanhoDoCampo() {
        assertThat(PixBrCode.ascii("São José dos Campos", 15)).isEqualTo("Sao Jose dos Ca");
    }

    @Test
    void txidInvalido() {
        assertThatThrownBy(() -> PixBrCode.of("chave", BigDecimal.ONE, "KAGE", "SP", "com espaço", true))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
