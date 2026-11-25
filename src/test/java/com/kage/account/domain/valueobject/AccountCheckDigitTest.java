package com.kage.account.domain.valueobject;

import com.kage.shared.domain.exception.ValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountCheckDigitTest {

    @ParameterizedTest
    @CsvSource({
            // 1*9+0*8+0*7+0*6+0*5+0*4+0*3+0*2 = 9 -> 11 - 9 = 2
            "10000000, 2",
            // 1*9+0*8+...+1*2 = 11 -> 11 % 11 = 0 -> 11 - 0 = 11 -> 0
            "10000001, 0",
            // 1*9+...+0*3+2*2 = 13 -> 13 % 11 = 2 -> 9
            "10000002, 9"
    })
    void of_deveCalcularModulo11(String number, String expected) {
        assertThat(AccountCheckDigit.of(number)).isEqualTo(expected);
    }

    @Test
    void of_deveDetectarTrocaDeDigitosVizinhos_queASomaSimplesNaoPegava() {
        assertThat(AccountCheckDigit.of("10000012")).isNotEqualTo(AccountCheckDigit.of("10000021"));
    }

    @Test
    void of_deveRecusarNumeroNaoNumerico() {
        assertThatThrownBy(() -> AccountCheckDigit.of("12a45")).isInstanceOf(ValidationException.class);
    }
}
