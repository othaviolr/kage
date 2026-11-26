package com.kage.card.domain.service;

import com.kage.shared.domain.valueobject.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IofCetPolicyTest {

    @Test
    void iofDoRotativo_adicionalMaisDiario() {
        // 600 por 31 dias: 2,28 + 600 × 0,0082% × 31 = 1,5252
        assertThat(IofPolicy.revolving(Money.of("600.00"), 31).amount()).isEqualByComparingTo("3.81");
        // sem dias, só o adicional
        assertThat(IofPolicy.revolving(Money.of("1000.00"), 0).amount()).isEqualByComparingTo("3.80");
    }

    @Test
    void iofDiario_paraEm365Dias() {
        // 0,38% + 0,0082% × 365 = 3,373% (o teto do diário é 2,993%)
        assertThat(IofPolicy.revolving(Money.of("1000.00"), 365).amount()).isEqualByComparingTo("33.73");
        assertThat(IofPolicy.revolving(Money.of("1000.00"), 1000).amount()).isEqualByComparingTo("33.73");
    }

    @Test
    void iofDoParcelamento_sobreCadaAmortizacao() {
        InstallmentPolicy.Quote quote = InstallmentPolicy.quoteInvoice(Money.of("1000.00"), 6);
        List<Money> amortizations = InstallmentPolicy.amortizations(quote);

        assertThat(amortizations).extracting(Money::amount).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("132.95"), new BigDecimal("144.90"), new BigDecimal("157.93"),
                        new BigDecimal("172.13"), new BigDecimal("187.60"), new BigDecimal("204.49"));
        assertThat(amortizations.stream().map(Money::amount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("1000.00");

        // vencimentos 20/07 a 20/12, contados de 20/06
        Money iof = IofPolicy.installments(quote.principal(), amortizations, List.of(30L, 61L, 92L, 122L, 153L, 183L));
        assertThat(iof.amount()).isEqualByComparingTo("13.19");
    }

    @Test
    void amortizacao_comJurosCortadosPeloTeto_usaATaxaImplicita() {
        InstallmentPolicy.Quote capped = InstallmentPolicy.capInterest(
                InstallmentPolicy.quoteInvoice(Money.of("1000.00"), 6), Money.ZERO);

        // sem juros: cada parcela amortiza o que vale
        assertThat(InstallmentPolicy.amortizations(capped)).extracting(Money::amount)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("166.70"), new BigDecimal("166.66"), new BigDecimal("166.66"),
                        new BigDecimal("166.66"), new BigDecimal("166.66"), new BigDecimal("166.66"));
    }

    @Test
    void cet_casosQueDaParaConferirDeCabeca() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        // pega 1000, devolve 1100 em 365 dias: 10% ao ano
        CetPolicy.Cet tenPercent = CetPolicy.of(Money.of("1000.00"), start,
                List.of(new CetPolicy.Flow(start.plusDays(365), Money.of("1100.00"))));
        assertThat(tenPercent.yearly()).isEqualByComparingTo("0.1000");

        // sem custo nenhum: CET zero
        CetPolicy.Cet free = CetPolicy.of(Money.of("1000.00"), start,
                List.of(new CetPolicy.Flow(start.plusDays(30), Money.of("1000.00"))));
        assertThat(free.yearly()).isEqualByComparingTo("0.0000");
    }

    @Test
    void cetDoRotativo_ficaAcimaDosJurosSozinhos() {
        CetPolicy.Cet cet = CetPolicy.revolvingReference(LocalDate.of(2026, 1, 20));

        // 1000 viram 1146,26 em 30 dias (14% + IOF de 6,26): ~14,8% ao mês, ~426% ao ano
        assertThat(cet.monthly()).isGreaterThan(new BigDecimal("0.1400")).isLessThan(new BigDecimal("0.1500"));
        assertThat(cet.yearly()).isGreaterThan(new BigDecimal("4.20")).isLessThan(new BigDecimal("4.35"));
    }

    @Test
    void cetDoParcelamento_ficaAcimaDosJurosSozinhos() {
        CetPolicy.Cet cet = CetPolicy.installmentReference(LocalDate.of(2026, 1, 20), 12);

        assertThat(cet.monthly()).isGreaterThan(new BigDecimal("0.0899")).isLessThan(new BigDecimal("0.0960"));
        assertThat(cet.yearly()).isGreaterThan(new BigDecimal("1.8094")); // (1,0899^12 - 1)
    }
}
