package com.kage.card.domain.service;

import com.kage.shared.domain.valueobject.Money;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class RevolvingCreditPolicyTest {

    @Test
    void minimo_deveSer15PorCento_comPisoDe50_semPassarDoTotal() {
        assertThat(RevolvingCreditPolicy.minimumPayment(Money.of("1000.00"))).isEqualTo(Money.of("150.00"));
        assertThat(RevolvingCreditPolicy.minimumPayment(Money.of("200.00"))).isEqualTo(Money.of("50.00")); // 15% = 30 < piso
        assertThat(RevolvingCreditPolicy.minimumPayment(Money.of("30.00"))).isEqualTo(Money.of("30.00"));  // piso > total
        assertThat(RevolvingCreditPolicy.minimumPayment(Money.ZERO)).isEqualTo(Money.ZERO);
    }

    @Test
    void pagouOMinimo_soJurosDoRotativo() {
        // fatura de 1000, pagou 400 (mínimo 150): 600 vão para o rotativo a 14%
        var charges = RevolvingCreditPolicy.chargesFor(Money.of("600.00"), Money.of("400.00"), Money.of("150.00"),
                LocalDate.of(2033, 1, 20), LocalDate.of(2033, 2, 10));

        assertThat(charges.revolvingInterest()).isEqualTo(Money.of("84.00"));
        assertThat(charges.lateFine()).isEqualTo(Money.ZERO);
        assertThat(charges.lateInterest()).isEqualTo(Money.ZERO);
        assertThat(charges.late()).isFalse();
    }

    @Test
    void naoPagouOMinimo_jurosMultaEMoraProRata() {
        // nada pago de 1000: juros 14% = 140; multa 2% = 20; mora 1% a.m. por 21 dias (20/01 -> 10/02) = 7,00
        var charges = RevolvingCreditPolicy.chargesFor(Money.of("1000.00"), Money.ZERO, Money.of("150.00"),
                LocalDate.of(2033, 1, 20), LocalDate.of(2033, 2, 10));

        assertThat(charges.revolvingInterest()).isEqualTo(Money.of("140.00"));
        assertThat(charges.lateFine()).isEqualTo(Money.of("20.00"));
        assertThat(charges.lateInterest()).isEqualTo(Money.of("7.00"));
        assertThat(charges.total()).isEqualTo(Money.of("167.00"));
        assertThat(charges.late()).isTrue();
    }

    @Test
    void pagouUmPoucoAbaixoDoMinimo_tambemEAtraso() {
        var charges = RevolvingCreditPolicy.chargesFor(Money.of("851.00"), Money.of("149.00"), Money.of("150.00"),
                LocalDate.of(2033, 1, 20), LocalDate.of(2033, 2, 10));

        assertThat(charges.late()).isTrue();
    }
}
