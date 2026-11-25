package com.kage.boleto.domain.valueobject;

import com.kage.shared.domain.exception.ValidationException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoletoCodeTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 25);
    private static final String FREE = "0001100000010000000000042";

    @Test
    void checkDigitsMatchHandCalculations() {
        // 1º campo do exemplo clássico do Banco do Brasil: 00190.0000[9]
        assertThat(BoletoCode.mod10("001900000")).isEqualTo(9);
        // pesos 2..9 da direita: 9*2+8*3+7*4+6*5+5*6+4*7+3*8+2*9+1*2 = 202; 202 % 11 = 4; 11 - 4 = 7
        assertThat(BoletoCode.mod11("123456789")).isEqualTo(7);
    }

    @Test
    void dueDateFactorRestartedAt1000On22February2025() {
        assertThat(BoletoCode.factorOf(LocalDate.of(2025, 2, 21))).isEqualTo("9999");
        assertThat(BoletoCode.factorOf(LocalDate.of(2025, 2, 22))).isEqualTo("1000");
        assertThat(BoletoCode.factorOf(TODAY)).isEqualTo("1641");
    }

    @Test
    void factorResolvesToTheCycleClosestToToday() {
        BoletoCode code = BoletoCode.build("001", LocalDate.of(2025, 2, 22), new BigDecimal("10.00"), FREE);

        assertThat(code.dueDate(TODAY)).contains(LocalDate.of(2025, 2, 22));
        // o mesmo fator 1000, visto de 2000, era 03/07/2000
        assertThat(code.dueDate(LocalDate.of(2000, 6, 1))).contains(LocalDate.of(2000, 7, 3));
    }

    @Test
    void buildsAndParsesBackFromBarcodeAndDigitableLine() {
        BoletoCode built = BoletoCode.build("999", TODAY.plusDays(10), new BigDecimal("150.75"), FREE);

        assertThat(built.barcode()).hasSize(44);
        assertThat(built.digitableLine()).hasSize(47);
        assertThat(BoletoCode.parse(built.barcode())).isEqualTo(built);
        assertThat(BoletoCode.parse(built.digitableLine())).isEqualTo(built);
        assertThat(BoletoCode.parse(built.formattedDigitableLine())).isEqualTo(built);

        assertThat(built.bankCode()).isEqualTo("999");
        assertThat(built.amount()).isEqualByComparingTo("150.75");
        assertThat(built.dueDate(TODAY)).contains(TODAY.plusDays(10));
        assertThat(built.freeField()).isEqualTo(FREE);
        assertThat(built.formattedDigitableLine()).matches("\\d{5}\\.\\d{5} \\d{5}\\.\\d{6} \\d{5}\\.\\d{6} \\d \\d{14}");
    }

    @Test
    void typoInTheDigitableLineIsCaughtByTheFieldCheckDigit() {
        String line = BoletoCode.build("999", TODAY, new BigDecimal("150.75"), FREE).digitableLine();
        String typo = line.substring(0, 12) + ((line.charAt(12) - '0' + 1) % 10) + line.substring(13);

        assertThatThrownBy(() -> BoletoCode.parse(typo)).isInstanceOf(ValidationException.class)
                .hasMessageContaining("Linha digitável inválida");
    }

    @Test
    void tamperedAmountInTheBarcodeFailsTheGeneralCheckDigit() {
        String barcode = BoletoCode.build("999", TODAY, new BigDecimal("150.75"), FREE).barcode();
        String tampered = barcode.substring(0, 18) + ((barcode.charAt(18) - '0' + 1) % 10) + barcode.substring(19);

        assertThatThrownBy(() -> BoletoCode.parse(tampered)).isInstanceOf(ValidationException.class)
                .hasMessageContaining("dígito verificador");
    }

    @Test
    void rejectsUtilityBillsAndGarbage() {
        assertThatThrownBy(() -> BoletoCode.parse("8".repeat(48))).hasMessageContaining("arrecadação");
        assertThatThrownBy(() -> BoletoCode.parse("123")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> BoletoCode.parse("abc")).isInstanceOf(ValidationException.class);
    }

    @Test
    void zeroFactorMeansNoDueDate() {
        String withoutDv = "001" + "9" + "0000" + "0000001000" + FREE;
        String barcode = withoutDv.substring(0, 4) + BoletoCode.mod11(withoutDv) + withoutDv.substring(4);

        assertThat(BoletoCode.parse(barcode).dueDate(TODAY)).isEmpty();
    }
}
