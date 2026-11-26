package com.kage.card.domain.entity;

import com.kage.card.domain.enums.InvoiceItemKind;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.ValidationException;
import com.kage.shared.domain.valueobject.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Créditos na fatura (contestação, saldo credor) e a retirada de uma compra contestada. */
class InvoiceCreditTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 9, 5, 12, 0);

    private Invoice invoice;

    @BeforeEach
    void setUp() {
        invoice = Invoice.open(UUID.randomUUID(), YearMonth.of(2026, 9), 10, 20);
    }

    @Test
    void credito_abateOTotal() {
        invoice.addItem(UUID.randomUUID(), "Mercado", Money.of("300.00"), AT);
        invoice.addCredit(InvoiceItemKind.DISPUTE_CREDIT, UUID.randomUUID(), "Crédito de contestação: Loja", Money.of("100.00"), AT);

        assertThat(invoice.charges().amount()).isEqualByComparingTo("300.00");
        assertThat(invoice.credits().amount()).isEqualByComparingTo("100.00");
        assertThat(invoice.total().amount()).isEqualByComparingTo("200.00");
        assertThat(invoice.creditBalance().amount()).isEqualByComparingTo("0.00");
        assertThat(invoice.absorbedCredits().amount()).isEqualByComparingTo("100.00");
    }

    @Test
    void creditoMaiorQueAsCobrancas_totalZero_eSobraViraSaldoCredor() {
        invoice.addItem(UUID.randomUUID(), "Padaria", Money.of("40.00"), AT);
        invoice.addCredit(InvoiceItemKind.DISPUTE_CREDIT, UUID.randomUUID(), "Crédito de contestação: Loja", Money.of("100.00"), AT);

        assertThat(invoice.total().amount()).isEqualByComparingTo("0.00");
        assertThat(invoice.creditBalance().amount()).isEqualByComparingTo("60.00");
        assertThat(invoice.absorbedCredits().amount()).isEqualByComparingTo("40.00");
        assertThat(invoice.minimumPayment().amount()).isEqualByComparingTo("0.00");
    }

    @Test
    void credito_ehIdempotentePelaOrigem() {
        UUID source = UUID.randomUUID();
        invoice.addCredit(InvoiceItemKind.DISPUTE_CREDIT, source, "Crédito", Money.of("100.00"), AT);
        invoice.addCredit(InvoiceItemKind.DISPUTE_CREDIT, source, "Crédito", Money.of("100.00"), AT);

        assertThat(invoice.getItems()).hasSize(1);
    }

    @Test
    void credito_soEmFaturaAberta_eSoTipoDeCredito() {
        assertThatThrownBy(() -> invoice.addCredit(InvoiceItemKind.LATE_FINE, UUID.randomUUID(), "x", Money.of("1.00"), AT))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> invoice.addCharge(InvoiceItemKind.DISPUTE_CREDIT, UUID.randomUUID(), "x", Money.of("1.00"), AT))
                .isInstanceOf(ValidationException.class);

        invoice.close();
        assertThatThrownBy(() -> invoice.addCredit(InvoiceItemKind.CREDIT_BALANCE, UUID.randomUUID(), "x", Money.of("1.00"), AT))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void creditoZero_naoViraLinha() {
        invoice.addCredit(InvoiceItemKind.CREDIT_BALANCE, UUID.randomUUID(), "x", Money.ZERO, AT);
        assertThat(invoice.isEmpty()).isTrue();
    }

    @Test
    void removePurchase_tiraALinhaDaCompra() {
        UUID purchaseId = UUID.randomUUID();
        invoice.addItem(purchaseId, "Loja", Money.of("80.00"), AT);
        invoice.addItem(UUID.randomUUID(), "Mercado", Money.of("20.00"), AT);

        var removed = invoice.removePurchase(purchaseId);

        assertThat(removed.amount().amount()).isEqualByComparingTo("80.00");
        assertThat(invoice.total().amount()).isEqualByComparingTo("20.00");
        assertThatThrownBy(() -> invoice.removePurchase(purchaseId)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void removePurchase_soDeFaturaAberta() {
        UUID purchaseId = UUID.randomUUID();
        invoice.addItem(purchaseId, "Loja", Money.of("80.00"), AT);
        invoice.close();

        assertThatThrownBy(() -> invoice.removePurchase(purchaseId)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void creditoQueCobreTudo_quitaSemPagamento() {
        invoice.addItem(UUID.randomUUID(), "Padaria", Money.of("40.00"), AT);
        invoice.addCredit(InvoiceItemKind.DISPUTE_CREDIT, UUID.randomUUID(), "Crédito", Money.of("40.00"), AT);
        invoice.close();

        assertThat(invoice.remaining().amount()).isEqualByComparingTo("0.00");
        invoice.pay();
        assertThat(invoice.getStatus().name()).isEqualTo("PAID");
        assertThat(invoice.getPaidAmount().amount()).isEqualByComparingTo("0.00");
    }
}
