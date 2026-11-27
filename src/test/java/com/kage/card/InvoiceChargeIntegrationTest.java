package com.kage.card;

import com.kage.AbstractIntegrationTest;
import com.kage.account.application.usecase.DepositAccount;
import com.kage.boleto.domain.valueobject.BoletoCode;
import com.kage.card.application.usecase.CloseInvoice;
import com.kage.card.application.usecase.GetCard;
import com.kage.card.application.usecase.GetInvoice;
import com.kage.card.application.usecase.InvoiceCharges;
import com.kage.card.application.usecase.IssueCard;
import com.kage.card.application.usecase.PayInvoice;
import com.kage.card.application.usecase.RegisterPurchase;
import com.kage.shared.domain.DomainTime;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.ValidationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pagar a fatura de outro banco, com Postgres real. Cartão de 5.000 que fecha dia 10 e vence dia 20;
 * compra de 1.000 em janeiro/2034, fatura fechada em 11/01.
 */
class InvoiceChargeIntegrationTest extends AbstractIntegrationTest {

    @Autowired IssueCard issueCard;
    @Autowired RegisterPurchase registerPurchase;
    @Autowired CloseInvoice closeInvoice;
    @Autowired GetInvoice getInvoice;
    @Autowired GetCard getCard;
    @Autowired InvoiceCharges invoiceCharges;
    @Autowired PayInvoice payInvoice;
    @Autowired DepositAccount depositAccount;

    @AfterEach
    void resetClock() {
        DomainTime.reset();
    }

    private static void now(LocalDateTime at) {
        ZoneId zone = ZoneId.systemDefault();
        DomainTime.use(Clock.fixed(at.atZone(zone).toInstant(), zone));
    }

    private record Setup(UUID cardId, UUID accountId) {}

    private Setup januaryClosed() {
        now(LocalDateTime.of(2034, 1, 5, 10, 0));
        IssueCard.Input input = issueCardInput(new BigDecimal("5000.00"), 10, 20);
        UUID cardId = issueCard.execute(input).cardId();
        registerPurchase.execute(new RegisterPurchase.Input(cardId, UUID.randomUUID(), "Mercado", new BigDecimal("1000.00"),
                LocalDateTime.of(2034, 1, 5, 10, 0)));
        now(LocalDateTime.of(2034, 1, 11, 10, 0));
        closeInvoice.execute(new CloseInvoice.Input(cardId, "2034-01"));
        return new Setup(cardId, input.accountId());
    }

    private GetInvoice.Output invoice(UUID cardId, String month) {
        return getInvoice.execute(new GetInvoice.Input(cardId, month));
    }

    private BigDecimal available(UUID cardId) {
        return getCard.execute(new GetCard.Input(cardId)).availableLimit();
    }

    @Test
    void pix_doValorInteiro_quitaAFatura_quandoODinheiroChega() {
        Setup s = januaryClosed();

        InvoiceCharges.ChargeView pix = invoiceCharges.issue(s.cardId(), "2034-01", "pix", null);

        assertThat(pix.method()).isEqualTo("PIX");
        assertThat(pix.status()).isEqualTo("PENDING");
        assertThat(pix.amount()).isEqualByComparingTo("1000.00");
        assertThat(pix.expiresAt()).isEqualTo(LocalDateTime.of(2034, 1, 12, 10, 0)); // 24 horas
        assertThat(pix.pixCopyPaste()).startsWith("000201010212").contains("54071000.00").contains(pix.reference());
        assertThat(pix.barcode()).isNull();
        assertThat(pix.note()).startsWith("Pague pelo app de qualquer banco até 12/01 às 10:00.");
        // gerar o código não paga nada
        assertThat(invoice(s.cardId(), "2034-01").status()).isEqualTo("CLOSED");
        assertThat(available(s.cardId())).isEqualByComparingTo("4000.00");

        now(LocalDateTime.of(2034, 1, 11, 10, 5));
        InvoiceCharges.Settlement settled = invoiceCharges.settle(pix.id(), null);

        assertThat(settled.status()).isEqualTo("PAID");
        assertThat(settled.appliedMonth()).isEqualTo("2034-01");
        assertThat(settled.invoiceStatus()).isEqualTo("PAID");
        assertThat(settled.creditAmount()).isEqualByComparingTo("0");
        assertThat(available(s.cardId())).isEqualByComparingTo("5000.00");
        assertThat(invoiceCharges.list(s.cardId(), "2034-01")).singleElement()
                .satisfies(c -> assertThat(c.note()).isEqualTo("Pago em 11/01."));
        // paga em dia: o cashback de 1% entra na fatura seguinte, como no pagamento pela conta
        assertThat(invoice(s.cardId(), "2034-02").items()).anySatisfy(i -> {
            assertThat(i.kind()).isEqualTo("CASHBACK");
            assertThat(i.amount()).isEqualByComparingTo("10.00");
        });
        // pagar de novo o mesmo código: não
        assertThatThrownBy(() -> invoiceCharges.settle(pix.id(), null)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void boleto_parcial_oNovoCancelaOAnterior_eValeADataEmQueFoiPago() {
        Setup s = januaryClosed();

        InvoiceCharges.ChargeView first = invoiceCharges.issue(s.cardId(), "2034-01", "BOLETO", new BigDecimal("400.00"));
        assertThat(first.barcode()).hasSize(44).startsWith("9999"); // banco 999, moeda 9
        assertThat(BoletoCode.parse(first.digitableLine().replaceAll("\\D", "")).amount()).isEqualByComparingTo("400.00");
        assertThat(first.expiresAt()).isEqualTo(LocalDateTime.of(2034, 1, 20, 23, 59, 59)); // o vencimento da fatura

        now(LocalDateTime.of(2034, 1, 11, 10, 1));
        InvoiceCharges.ChargeView second = invoiceCharges.issue(s.cardId(), "2034-01", "BOLETO", new BigDecimal("300.00"));
        assertThat(invoiceCharges.list(s.cardId(), "2034-01")).extracting(InvoiceCharges.ChargeView::status)
                .containsExactly("PENDING", "CANCELLED"); // do mais novo para o mais antigo
        assertThatThrownBy(() -> invoiceCharges.settle(first.id(), null)).isInstanceOf(BusinessRuleException.class);

        // pagou no dia do vencimento; a compensação chega dois dias depois
        now(LocalDateTime.of(2034, 1, 22, 9, 0));
        InvoiceCharges.Settlement settled = invoiceCharges.settle(second.id(), LocalDateTime.of(2034, 1, 20, 15, 0));

        assertThat(settled.paidAt()).isEqualTo(LocalDateTime.of(2034, 1, 20, 15, 0));
        assertThat(settled.invoiceStatus()).isEqualTo("CLOSED");
        assertThat(settled.invoiceRemaining()).isEqualByComparingTo("700.00");
        assertThat(available(s.cardId())).isEqualByComparingTo("4300.00");
    }

    @Test
    void dinheiroQueChegaDepoisDeAFaturaSerPaga_viraCreditoNaFaturaAtual() {
        Setup s = januaryClosed();
        InvoiceCharges.ChargeView pix = invoiceCharges.issue(s.cardId(), "2034-01", "PIX", null);
        depositAccount.execute(new DepositAccount.Input(s.accountId(), new BigDecimal("1000.00")));
        payInvoice.execute(new PayInvoice.Input(s.cardId(), "2034-01")); // pagou pela conta também

        InvoiceCharges.Settlement settled = invoiceCharges.settle(pix.id(), null);

        assertThat(settled.appliedMonth()).isNull();
        assertThat(settled.appliedAmount()).isEqualByComparingTo("0");
        assertThat(settled.creditAmount()).isEqualByComparingTo("1000.00");
        assertThat(settled.creditMonth()).isEqualTo("2034-02");
        assertThat(invoice(s.cardId(), "2034-02").items()).anySatisfy(i -> {
            assertThat(i.kind()).isEqualTo("CREDIT_BALANCE");
            assertThat(i.amount()).isEqualByComparingTo("1000.00");
        });
    }

    @Test
    void codigoVencido_eRecusas() {
        Setup s = januaryClosed();
        InvoiceCharges.ChargeView pix = invoiceCharges.issue(s.cardId(), "2034-01", "PIX", new BigDecimal("100.00"));
        now(LocalDateTime.of(2034, 1, 13, 10, 0));
        assertThat(invoiceCharges.list(s.cardId(), "2034-01").get(0).status()).isEqualTo("EXPIRED");
        assertThatThrownBy(() -> invoiceCharges.settle(pix.id(), LocalDateTime.of(2034, 1, 12, 11, 0)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("vencida");

        assertThatThrownBy(() -> invoiceCharges.issue(s.cardId(), "2034-01", "TED", null))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> invoiceCharges.issue(s.cardId(), "2034-01", "PIX", new BigDecimal("1000.01")))
                .isInstanceOf(BusinessRuleException.class);
        registerPurchase.execute(new RegisterPurchase.Input(s.cardId(), UUID.randomUUID(), "Padaria", new BigDecimal("20.00"),
                LocalDateTime.of(2034, 1, 13, 10, 0)));
        assertThatThrownBy(() -> invoiceCharges.issue(s.cardId(), "2034-02", "PIX", null)) // aberta
                .isInstanceOf(BusinessRuleException.class);
    }
}
