package com.kage.auth.domain.valueobject;

import com.kage.shared.domain.exception.ValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordPolicyTest {

    private static final String CPF = "52998224725";

    @ParameterizedTest
    @CsvSource({
            "abc123, pelo menos 8",
            "abcdefgh, letras e números",
            "12345678, letras e números",
            "kage998224, CPF"
    })
    void deveRecusar(String password, String message) {
        assertThatThrownBy(() -> PasswordPolicy.validate(password, CPF))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining(message);
    }

    @Test
    void deveRecusarSenhaNulaOuLongaDemais() {
        assertThatThrownBy(() -> PasswordPolicy.validate(null, CPF)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> PasswordPolicy.validate("a1".repeat(37), CPF))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("no máximo");
    }

    @Test
    void deveAceitarSenhaForte() {
        assertThatCode(() -> PasswordPolicy.validate("Sombra-2026", CPF)).doesNotThrowAnyException();
    }
}
