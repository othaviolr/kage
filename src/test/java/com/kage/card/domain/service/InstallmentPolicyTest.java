package com.kage.card.domain.service;

import com.kage.shared.domain.exception.ValidationException;
import com.kage.shared.domain.valueobject.Money;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InstallmentPolicyTest {

    @Test
    void semJuros_centavosVaoNaPrimeiraParcela_eASomaFecha() {
        List<Money> parts = InstallmentPolicy.splitWithoutInterest(Money.of("100.00"), 3);

        assertThat(parts).containsExactly(Money.of("33.34"), Money.of("33.33"), Money.of("33.33"));
        assertThat(parts.stream().reduce(Money.ZERO, Money::add)).isEqualTo(Money.of("100.00"));
    }

    @Test
    void semJuros_limitesDeParcelas() {
        assertThat(InstallmentPolicy.splitWithoutInterest(Money.of("50.00"), 1)).containsExactly(Money.of("50.00"));
        assertThatThrownBy(() -> InstallmentPolicy.splitWithoutInterest(Money.of("100.00"), 13)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> InstallmentPolicy.splitWithoutInterest(Money.of("100.00"), 0)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> InstallmentPolicy.splitWithoutInterest(Money.of("0.02"), 3))
                .isInstanceOf(ValidationException.class).hasMessageContaining("pequeno demais");
    }

    @Test
    void tabelaPrice_valoresConferidosForaDoCodigo() {
        // PMT = 1000 * 0,0899 * 1,0899^6 / (1,0899^6 - 1) = 222,8533 -> 222,85
        InstallmentPolicy.Quote six = InstallmentPolicy.quoteInvoice(Money.of("1000.00"), 6);

        assertThat(six.amounts()).allMatch(m -> m.equals(Money.of("222.85")));
        assertThat(six.total()).isEqualTo(Money.of("1337.10"));
        assertThat(six.interest()).isEqualTo(Money.of("337.10"));

        // 12x: 139,5803 -> 139,58
        assertThat(InstallmentPolicy.quoteInvoice(Money.of("1000.00"), 12).amounts().get(11)).isEqualTo(Money.of("139.58"));
    }

    @Test
    void parcelamentoDaFatura_deveSerMaisBaratoQueORotativo() {
        // 6 meses de rotativo a 14% a.m. sobre 1000 custariam ~1195 de juros (1,14^6 - 1); parcelado, 337
        InstallmentPolicy.Quote quote = InstallmentPolicy.quoteInvoice(Money.of("1000.00"), 6);
        assertThat(quote.interest().isLessThan(Money.of("400.00"))).isTrue();
    }

    @Test
    void teto_cortaOsJurosERedistribuiAsParcelas() {
        InstallmentPolicy.Quote capped = InstallmentPolicy.capInterest(InstallmentPolicy.quoteInvoice(Money.of("1000.00"), 6),
                Money.of("100.00"));

        assertThat(capped.interest()).isEqualTo(Money.of("100.00"));
        assertThat(capped.total()).isEqualTo(Money.of("1100.00"));
        assertThat(capped.amounts().stream().reduce(Money.ZERO, Money::add)).isEqualTo(Money.of("1100.00"));
        // 1100/6: base 183,33 (x6 = 1099,98); os 0,02 que sobram vão na 1ª parcela
        assertThat(capped.amounts().get(0)).isEqualTo(Money.of("183.35"));
        assertThat(capped.amounts().get(5)).isEqualTo(Money.of("183.33"));
    }

    @Test
    void parcelamentoDaFatura_limitesDeParcelas() {
        assertThatThrownBy(() -> InstallmentPolicy.quoteInvoice(Money.of("1000.00"), 1)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> InstallmentPolicy.quoteInvoice(Money.of("1000.00"), 13)).isInstanceOf(ValidationException.class);
    }
}
