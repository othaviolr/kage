package com.kage.shared.domain.valueobject;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class ReceiptValuesTest {

    @Test
    void cpf_mascarado_cnpj_inteiro() {
        assertThat(DocumentMask.mask("123.456.789-09")).isEqualTo("***.456.789-**");
        assertThat(DocumentMask.mask("12345678909")).isEqualTo("***.456.789-**");
        assertThat(DocumentMask.mask("11222333000181")).isEqualTo("11.222.333/0001-81");
        assertThat(DocumentMask.mask(null)).isNull();
    }

    @Test
    void chavePix_soOCpfEMascarado() {
        assertThat(DocumentMask.maskPixKey("123.456.789-09")).isEqualTo("***.456.789-**");
        assertThat(DocumentMask.maskPixKey("maria@kage.com")).isEqualTo("maria@kage.com");
        assertThat(DocumentMask.maskPixKey("+5541999991234")).isEqualTo("+5541999991234"); // telefone tem +
        assertThat(DocumentMask.maskPixKey("8f1c2a90-77aa-4d1e-9b0c-1234567890ab")).isEqualTo("8f1c2a90-77aa-4d1e-9b0c-1234567890ab");
    }

    @Test
    void codigoDeAutenticacao_deterministico_eMudaComQualquerCampo() {
        LocalDateTime at = LocalDateTime.of(2026, 11, 25, 10, 30);
        String code = ReceiptCode.of("PIX", "E2E123", new BigDecimal("120.00"), at);

        assertThat(code).matches("[0-9A-F]{4}(-[0-9A-F]{4}){7}");
        assertThat(ReceiptCode.of("PIX", "E2E123", new BigDecimal("120.00"), at)).isEqualTo(code);
        assertThat(ReceiptCode.of("PIX", "E2E123", new BigDecimal("1200.00"), at)).isNotEqualTo(code); // valor editado
        assertThat(ReceiptCode.of("PIX", "E2E123", new BigDecimal("120.00"), at.plusMinutes(1))).isNotEqualTo(code);
    }
}
