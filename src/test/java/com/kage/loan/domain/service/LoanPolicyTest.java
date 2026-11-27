package com.kage.loan.domain.service;

import com.kage.shared.domain.exception.ValidationException;
import com.kage.shared.domain.valueobject.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import com.kage.card.domain.service.CetPolicy;

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

        // Price de 1000 a 3,99%, 1ª parcela a 35 dias (juros pro rata: 1000 × (1,0399^(35/30) - 1) = 46,70):
        // parcela 362,64, amortizações 315,94 / 335,35 / 348,71 a 35/63/94 dias
        // IOF = 3,80 + 0,0082% × (315,94×35 + 335,35×63 + 348,71×94) = 3,80 + 5,33
        assertThat(LoanPolicy.price(Money.of("1000.00"), 3, CONTRACT, LoanPolicy.dueDates(CONTRACT, 5, 3), LoanPolicy.MONTHLY_RATE))
                .extracting(i -> i.interest().amount().toPlainString(), i -> i.principal().amount().toPlainString())
                .containsExactly(org.assertj.core.groups.Tuple.tuple("46.70", "315.94"),
                        org.assertj.core.groups.Tuple.tuple("27.29", "335.35"),
                        org.assertj.core.groups.Tuple.tuple("13.91", "348.71"));
        assertThat(q.iof().amount()).isEqualByComparingTo("9.13");
        assertThat(q.released().amount()).isEqualByComparingTo("1000.00");
        assertThat(q.financed().amount()).isEqualByComparingTo("1009.13");
        assertThat(q.schedule()).hasSize(3);
        // a soma do principal das parcelas fecha no financiado
        assertThat(q.schedule().stream().map(i -> i.principal().amount()).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("1009.13");
        // parcelas iguais (a última acerta centavos)
        assertThat(q.schedule().get(0).amount()).isEqualTo(q.schedule().get(1).amount());
        assertThat(q.totalInterest().amount())
                .isEqualByComparingTo(q.totalPaid().amount().subtract(new BigDecimal("1009.13")));
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
        // dívida de 1000 em 3x (as mesmas amortizações da cotação acima: 315,94 / 335,35 / 348,71),
        // contrato original de 01/11/2025: as parcelas novas vencem a 96, 124 e 155 dias dele
        LocalDate original = LocalDate.of(2025, 11, 1);
        LoanPolicy.Quote q = LoanPolicy.renegotiation(Money.of("1000.00"), 3, 5, CONTRACT, LoanPolicy.MONTHLY_RATE,
                original, Money.of("1000.00"), 100);

        // o IOF já cobriu 100 dias: complementa 0, 24 e 55 dias; sem os 0,38% de novo
        // 0,0082% × (335,35×24 + 348,71×55) = 2,23
        assertThat(q.iof().amount()).isEqualByComparingTo("2.23");
        assertThat(q.released().amount()).isEqualByComparingTo("1000.00");
        assertThat(q.financed().amount()).isEqualByComparingTo("1002.23");
        assertThat(q.monthlyRate()).isEqualByComparingTo(LoanPolicy.MONTHLY_RATE);
        assertThat(q.schedule().stream().map(i -> i.principal().amount()).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("1002.23");

        // metade da dívida é encargo (não foi tributado no contrato): IOF só sobre a parte do principal
        assertThat(LoanPolicy.renegotiation(Money.of("1000.00"), 3, 5, CONTRACT, LoanPolicy.MONTHLY_RATE, original,
                Money.of("500.00"), 100).iof().amount()).isEqualByComparingTo("1.12");
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
    @Test
    void primeiroPeriodo_juroProRata_oCustoAcompanhaOPrazo() {
        // 30 dias: a Price de sempre (juros de um mês no 1º período)
        LocalDate start = LocalDate.of(2026, 1, 10);
        List<LoanPolicy.Installment> month = LoanPolicy.price(Money.of("1000.00"), 3, start,
                List.of(LocalDate.of(2026, 2, 9), LocalDate.of(2026, 3, 9), LocalDate.of(2026, 4, 9)), LoanPolicy.MONTHLY_RATE);
        assertThat(month.get(0).interest().amount()).isEqualByComparingTo("39.90");

        // 58 dias de carência: o 1º período leva os juros dos 58 dias, e a parcela sobe
        List<LoanPolicy.Installment> longer = LoanPolicy.price(Money.of("1000.00"), 3, start,
                List.of(LocalDate.of(2026, 3, 9), LocalDate.of(2026, 4, 9), LocalDate.of(2026, 5, 9)), LoanPolicy.MONTHLY_RATE);
        assertThat(longer.get(0).interest().amount()).isEqualByComparingTo("78.58"); // 1000 × (1,0399^(58/30) - 1)
        assertThat(longer.get(0).amount().amount()).isGreaterThan(month.get(0).amount().amount());

        // sem IOF, o custo efetivo não cai abaixo da taxa do contrato com a carência longa (com juros de um mês
        // só nos 58 dias, ele era 2,71% a.m.); fica um pouco acima porque o CET conta o mês como 365/12 dias
        // e fevereiro é curto
        for (List<LoanPolicy.Installment> schedule : List.of(month, longer)) {
            CetPolicy.Cet cet = CetPolicy.of(Money.of("1000.00"), start,
                    schedule.stream().map(i -> new CetPolicy.Flow(i.dueDate(), i.amount())).toList());
            assertThat(cet.monthly()).isBetween(LoanPolicy.MONTHLY_RATE, new BigDecimal("0.0420"));
        }
    }

    @Test
    void carenciaLongaEmPrazoLongo_juroQueNaoCabeNaParcelaFicaParaASeguinte_semAmortizacaoNegativa() {
        // 50 mil em 24x com 57 dias até a 1ª (09/02 fica a 29 dias, então vai para 09/03): os juros do
        // 1º período (~3.858) passam da parcela (~3.393)
        LocalDate start = LocalDate.of(2026, 1, 11);
        List<LoanPolicy.Installment> schedule = LoanPolicy.price(Money.of("50000.00"), 24, start,
                LoanPolicy.dueDates(start, 9, 24), LoanPolicy.MONTHLY_RATE);

        assertThat(schedule.get(0).dueDate()).isEqualTo(LocalDate.of(2026, 3, 9));
        assertThat(schedule.get(0).principal().amount()).isEqualByComparingTo("0.00"); // a 1ª é só juros
        assertThat(schedule.get(0).amount()).isEqualTo(schedule.get(1).amount());       // e tem o mesmo valor
        assertThat(schedule).allSatisfy(i -> assertThat(i.principal().amount().signum()).isNotNegative());
        assertThat(schedule.stream().map(i -> i.principal().amount()).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("50000.00");
        // os juros adiados rendem juros: a última fecha a conta com a mesma parcela, a menos do arredondamento da
        // parcela em 24 meses (R$ 0,20 aqui; a Price de 30 dias de sempre dá R$ 0,21 nestes 50 mil)
        assertThat(schedule.get(23).amount().amount().subtract(schedule.get(0).amount().amount()).abs())
                .isLessThan(new BigDecimal("0.50"));
        // e a oferta máxima em 24x volta a funcionar
        assertThat(LoanPolicy.maxAmount(Money.of("5000.00"), 24, 9, start).amount()).isEqualByComparingTo("50000");
    }
}
