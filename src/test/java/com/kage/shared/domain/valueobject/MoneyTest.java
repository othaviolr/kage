package com.kage.shared.domain.valueobject;

import com.kage.shared.domain.exception.DomainException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void of_deveRejeitarValorNegativo() {
        assertThatThrownBy(() -> Money.of("-1.00"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void of_deveArredondarParaDuasCasasDecimais_comHalfUp() {
        Money money = Money.of("10.005");

        assertThat(money.amount()).isEqualByComparingTo("10.01");
    }

    @Test
    void add_deveSomarValoresCorretamente() {
        Money result = Money.of("10.00").add(Money.of("5.50"));

        assertThat(result.amount()).isEqualByComparingTo("15.50");
    }

    @Test
    void subtract_deveLancarDomainException_quandoResultariaEmSaldoNegativo() {
        assertThatThrownBy(() -> Money.of("10.00").subtract(Money.of("20.00")))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("insuficiente");
    }

    @Test
    void isGreaterThan_eIsLessThan_devemCompararCorretamente() {
        assertThat(Money.of("10.00").isGreaterThan(Money.of("5.00"))).isTrue();
        assertThat(Money.of("5.00").isLessThan(Money.of("10.00"))).isTrue();
    }

    @Test
    void isZero_deveRetornarTrue_quandoValorEhZero() {
        assertThat(Money.ZERO.isZero()).isTrue();
    }
}
