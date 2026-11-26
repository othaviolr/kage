package com.kage.card.domain.service;

import com.kage.card.domain.entity.Invoice;
import com.kage.card.domain.enums.InvoiceItemKind;
import com.kage.shared.domain.valueobject.Money;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Cartão fecha dia 10 e vence dia 20; compra contestada de 400 numa fatura de 1000. */
class DisputeInterestPolicyTest {

    private static final LocalDateTime AT = LocalDateTime.of(2034, 1, 5, 10, 0);

    private final UUID cardId = UUID.randomUUID();
    private final UUID disputed = UUID.randomUUID();

    private Invoice january() {
        Invoice january = Invoice.open(cardId, YearMonth.of(2034, 1), 10, 20);
        january.addItem(disputed, "Loja", Money.of("400.00"), AT);
        january.addItem(UUID.randomUUID(), "Mercado", Money.of("600.00"), AT);
        january.close();
        return january;
    }

    /** Leva o saldo de {@code from} para {@code to} com os encargos, como o fechamento faz. */
    private static void roll(Invoice from, Invoice to, String interest, String fine) {
        Money carried = from.rollOver(AT);
        to.addCharge(InvoiceItemKind.PREVIOUS_BALANCE, from.getId(), "Saldo anterior", carried, AT);
        to.addCharge(InvoiceItemKind.REVOLVING_INTEREST, from.getId(), "Juros", Money.of(interest), AT);
        to.addCharge(InvoiceItemKind.LATE_FINE, from.getId(), "Multa", Money.of(fine), AT);
    }

    @Test
    void faturaQuitada_semEncargo() {
        Invoice january = january();
        january.pay();

        assertThat(DisputeInterestPolicy.refundFor(List.of(january), disputed).total().amount()).isEqualByComparingTo("0.00");
    }

    @Test
    void compraAindaEmFaturaAberta_semEncargo() {
        Invoice open = Invoice.open(cardId, YearMonth.of(2034, 1), 10, 20);
        open.addItem(disputed, "Loja", Money.of("400.00"), AT);

        assertThat(DisputeInterestPolicy.refundFor(List.of(open), disputed).total().amount()).isEqualByComparingTo("0.00");
    }

    @Test
    void rotativo_devolveAParteProporcionalDosEncargos() {
        Invoice january = january();
        january.registerPayment(Money.of("400.00"), AT); // sobram 600
        Invoice february = Invoice.open(cardId, YearMonth.of(2034, 2), 10, 20);
        roll(january, february, "84.00", "0.00");
        february.close();
        february.pay();

        // 84 de juros × 400/1000 da fatura de janeiro
        assertThat(DisputeInterestPolicy.refundFor(List.of(january, february), disputed).total().amount())
                .isEqualByComparingTo("33.60");
    }

    @Test
    void rotativoQueGirouDeNovo_seguiACorrente() {
        Invoice january = january();
        Invoice february = Invoice.open(cardId, YearMonth.of(2034, 2), 10, 20);
        roll(january, february, "140.00", "20.00"); // 1000 + 160 = 1160
        february.close();
        Invoice march = Invoice.open(cardId, YearMonth.of(2034, 3), 10, 20);
        roll(february, march, "162.40", "23.20");
        march.close();
        march.pay();

        // janeiro: 160 × 0,4 = 64; a parte em fevereiro é 400 + 64 = 464 de 1160 (0,4): 185,60 × 0,4 = 74,24
        assertThat(DisputeInterestPolicy.refundFor(List.of(january, february, march), disputed).total().amount())
                .isEqualByComparingTo("138.24");
    }

    @Test
    void rotativoComIof_devolveOIofSeparadoDosEncargos() {
        Invoice january = january();
        january.registerPayment(Money.of("400.00"), AT);
        Invoice february = Invoice.open(cardId, YearMonth.of(2034, 2), 10, 20);
        roll(january, february, "84.00", "0.00");
        february.addCharge(InvoiceItemKind.IOF, january.getId(), "IOF do rotativo", Money.of("3.81"), AT);
        february.close();
        february.pay();

        DisputeInterestPolicy.Refund refund = DisputeInterestPolicy.refundFor(List.of(january, february), disputed);

        assertThat(refund.charges().amount()).isEqualByComparingTo("33.60"); // 84 × 0,4: sai do teto legal
        assertThat(refund.iof().amount()).isEqualByComparingTo("1.52");      // 3,81 × 0,4: imposto, fora do teto
        assertThat(refund.total().amount()).isEqualByComparingTo("35.12");
    }

    @Test
    void parcelamentoDaFatura_devolveOsJurosDaParteDaCompra() {
        Invoice january = january();
        january.finance(AT); // 1000 parcelados
        Invoice february = Invoice.open(cardId, YearMonth.of(2034, 2), 10, 20);
        Invoice march = Invoice.open(cardId, YearMonth.of(2034, 3), 10, 20);
        february.addInvoiceInstallment(january.getId(), "Parcela 1/2", Money.of("560.00"), AT, 1, 2);
        march.addInvoiceInstallment(january.getId(), "Parcela 2/2", Money.of("560.00"), AT, 2, 2);

        // juros do plano: 1120 - 1000 = 120; × 0,4
        assertThat(DisputeInterestPolicy.refundFor(List.of(january, february, march), disputed).total().amount())
                .isEqualByComparingTo("48.00");
    }

    @Test
    void parcelamentoComParcelaAntecipada_contaSoAsQueRestam() {
        Invoice january = january();
        january.finance(AT);
        Invoice february = Invoice.open(cardId, YearMonth.of(2034, 2), 10, 20);
        february.addInvoiceInstallment(january.getId(), "Parcela 1/2", Money.of("560.00"), AT, 1, 2);
        // a 2ª foi antecipada (saiu do plano, com desconto dos juros)

        // juros por parcela: 60; resta 1 parcela; × 0,4
        assertThat(DisputeInterestPolicy.refundFor(List.of(january, february), disputed).total().amount())
                .isEqualByComparingTo("24.00");
    }
}
