package com.kage.loan.domain.service;

import com.kage.shared.domain.exception.ValidationException;
import com.kage.shared.domain.valueobject.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoanPolicyTest {

    private static final LocalDate CONTRACT = LocalDate.of(2026, 1, 1);

    @Test
    void primeiraParcela_peloMenos30DiasDepois() {
        // dia 5: 05/01 está a 4 dias, então a 1ª vai para 05/02
        assertThat(LoanPolicy.dueDates(CONTRACT, 5, 3))
                .containsExactly(LocalDate.of(2026, 2, 5), LocalDate.of(2026, 3, 5), LocalDate.of(2026, 4, 5));
        // dia 28: 28/01 está a 27 dias, então vai para 28/02
        assertThat(LoanPolicy.dueDates(CONTRACT, 28, 1)).containsExactly(LocalDate.of(2026, 2, 28));
    }

    @Test
    void cotacao_iofFinanciado_eConferidoAMao() {
        LoanPolicy.Quote q = LoanPolicy.quote(Money.of("1000.00"), 3, 5, CONTRACT);

        // Price de 1000 a 3,99%: amortizações 320,38 / 333,16 / 346,46 a 35/63/94 dias
        // IOF = 3,80 + 0,0082% × (320,38×35 + 333,16×63 + 346,46×94) = 3,80 + 5,31
        assertThat(q.iof().amount()).isEqualByComparingTo("9.11");
        assertThat(q.released().amount()).isEqualByComparingTo("1000.00");
        assertThat(q.financed().amount()).isEqualByComparingTo("1009.11");
        assertThat(q.schedule()).hasSize(3);
        // a soma do principal das parcelas fecha no financiado
        assertThat(q.schedule().stream().map(i -> i.principal().amount()).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("1009.11");
        // parcelas iguais (a última acerta centavos)
        assertThat(q.schedule().get(0).amount()).isEqualTo(q.schedule().get(1).amount());
        assertThat(q.totalInterest().amount())
                .isEqualByComparingTo(q.totalPaid().amount().subtract(new BigDecimal("1009.11")));
        // CET acima da taxa de juros (o IOF entra, e o cliente recebe só os 1000)
        assertThat(q.cetMonthly()).isGreaterThan(LoanPolicy.MONTHLY_RATE);
    }

    @Test
    void limitesDoProduto() {
        assertThatThrownBy(() -> LoanPolicy.quote(Money.of("199.99"), 3, 5, CONTRACT)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> LoanPolicy.quote(Money.of("50000.01"), 12, 5, CONTRACT)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> LoanPolicy.quote(Money.of("1000.00"), 2, 5, CONTRACT)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> LoanPolicy.quote(Money.of("1000.00"), 25, 5, CONTRACT)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> LoanPolicy.quote(Money.of("1000.00"), 12, 29, CONTRACT)).isInstanceOf(ValidationException.class);
    }

    @Test
    void valorMaximo_temParcelaQueCabe_eUmRealAMaisJaNaoCabe() {
        Money maxInstallment = Money.of("600.00");

        Money max = LoanPolicy.maxAmount(maxInstallment, 24, 10, CONTRACT);

        assertThat(LoanPolicy.quote(max, 24, 10, CONTRACT).installmentAmount().isGreaterThan(maxInstallment)).isFalse();
        assertThat(LoanPolicy.quote(max.add(Money.of("1.00")), 24, 10, CONTRACT).installmentAmount()
                .isGreaterThan(maxInstallment)).isTrue();
        assertThat(LoanPolicy.maxAmount(Money.ZERO, 24, 10, CONTRACT).isZero()).isTrue();
        assertThat(LoanPolicy.maxAmount(Money.of("100000.00"), 24, 10, CONTRACT)).isEqualTo(LoanPolicy.MAX_AMOUNT);
    }

    @Test
    void atraso_multaMaisMoraProRata() {
        // 500 com 15 dias de atraso: 2% (10,00) + 1% a.m. × 15/30 (2,50)
        assertThat(LoanPolicy.lateCharges(Money.of("500.00"), LocalDate.of(2026, 3, 5), LocalDate.of(2026, 3, 20)).amount())
                .isEqualByComparingTo("12.50");
        assertThat(LoanPolicy.lateCharges(Money.of("500.00"), LocalDate.of(2026, 3, 5), LocalDate.of(2026, 3, 5)).isZero()).isTrue();
    }

    @Test
    void antecipacao_valorPresentePelaTaxaDoContrato() {
        // 30 dias antes: 500 / 1,0399
        assertThat(LoanPolicy.presentValue(Money.of("500.00"), LoanPolicy.MONTHLY_RATE, LocalDate.of(2026, 3, 31),
                LocalDate.of(2026, 3, 1)).amount()).isEqualByComparingTo("480.82");
        assertThat(LoanPolicy.presentValue(Money.of("500.00"), LoanPolicy.MONTHLY_RATE, LocalDate.of(2026, 3, 1),
                LocalDate.of(2026, 3, 1)).amount()).isEqualByComparingTo("500.00");
    }
    @Test
    void renegociacao_soOIofDiarioComplementar_conferidoAMao() {
        // dívida de 1000 em 3x (as mesmas amortizações da cotação acima: 320,38 / 333,16 / 346,46),
        // contrato original de 01/11/2025: as parcelas novas vencem a 96, 124 e 155 dias dele
        LocalDate original = LocalDate.of(2025, 11, 1);
        LoanPolicy.Quote q = LoanPolicy.renegotiation(Money.of("1000.00"), 3, 5, CONTRACT, LoanPolicy.MONTHLY_RATE,
                original, Money.of("1000.00"), 100);

        // o IOF já cobriu 100 dias: complementa 0, 24 e 55 dias; sem os 0,38% de novo
        // 0,0082% × (333,16×24 + 346,46×55) = 2,22
        assertThat(q.iof().amount()).isEqualByComparingTo("2.22");
        assertThat(q.released().amount()).isEqualByComparingTo("1000.00");
        assertThat(q.financed().amount()).isEqualByComparingTo("1002.22");
        assertThat(q.monthlyRate()).isEqualByComparingTo(LoanPolicy.MONTHLY_RATE);
        assertThat(q.schedule().stream().map(i -> i.principal().amount()).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("1002.22");

        // metade da dívida é encargo (não foi tributado no contrato): IOF só sobre a parte do principal
        assertThat(LoanPolicy.renegotiation(Money.of("1000.00"), 3, 5, CONTRACT, LoanPolicy.MONTHLY_RATE, original,
                Money.of("500.00"), 100).iof().amount()).isEqualByComparingTo("1.11");
        // o contrato já tinha cobrado os 365 dias: nada a complementar
        LoanPolicy.Quote covered = LoanPolicy.renegotiation(Money.of("1000.00"), 3, 5, CONTRACT, LoanPolicy.MONTHLY_RATE,
                original, Money.of("1000.00"), 365);
        assertThat(covered.iof().amount()).isEqualByComparingTo("0.00");
        assertThat(covered.financed().amount()).isEqualByComparingTo("1000.00");
    }

    @Test
    void renegociacao_validaParcelasDiaESaldo() {
        LocalDate original = LocalDate.of(2025, 11, 1);
        assertThatThrownBy(() -> LoanPolicy.renegotiation(Money.of("1000.00"), 2, 5, CONTRACT, LoanPolicy.MONTHLY_RATE,
                original, Money.ZERO, 0)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> LoanPolicy.renegotiation(Money.of("1000.00"), 6, 29, CONTRACT, LoanPolicy.MONTHLY_RATE,
                original, Money.ZERO, 0)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> LoanPolicy.renegotiation(Money.ZERO, 6, 5, CONTRACT, LoanPolicy.MONTHLY_RATE,
                original, Money.ZERO, 0)).isInstanceOf(ValidationException.class);
    }
}
