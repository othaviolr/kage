package com.kage.card;

import com.kage.AbstractIntegrationTest;
import com.kage.account.application.usecase.DepositAccount;
import com.kage.boleto.application.usecase.BoletoReceipts;
import com.kage.boleto.application.usecase.PayBoletos;
import com.kage.card.application.usecase.CloseInvoice;
import com.kage.card.application.usecase.GetCard;
import com.kage.card.application.usecase.GetInvoice;
import com.kage.card.application.usecase.InvoiceCharges;
import com.kage.card.application.usecase.IssueCard;
import com.kage.card.application.usecase.RegisterPurchase;
import com.kage.shared.domain.DomainTime;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.ValidationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boleto da fatura pago pelo "pagar boleto" da conta Kage, com Postgres real: o débito do pagador e o
 * pagamento da fatura na mesma transação, sem esperar compensação. Cartão de 5.000 que fecha dia 10 e
 * vence dia 20; compra de 1.000 em janeiro/2034, fatura fechada em 11/01.
 */
class InvoiceBoletoPaymentIntegrationTest extends AbstractIntegrationTest {

    @Autowired IssueCard issueCard;
    @Autowired RegisterPurchase registerPurchase;
    @Autowired CloseInvoice closeInvoice;
    @Autowired GetInvoice getInvoice;
    @Autowired GetCard getCard;
    @Autowired InvoiceCharges invoiceCharges;
    @Autowired PayBoletos payBoletos;
    @Autowired BoletoReceipts boletoReceipts;
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

    private UUID fundedAccount(UUID accountId, String amount) {
        depositAccount.execute(new DepositAccount.Input(accountId, new BigDecimal(amount)));
        return accountId;
    }

    private GetInvoice.Output january(UUID cardId) {
        return getInvoice.execute(new GetInvoice.Input(cardId, "2034-01"));
    }

    @Test
    void boletoDaFatura_pagoPelaConta_quitaAFaturaNaHora() {
        Setup s = januaryClosed();
        fundedAccount(s.accountId(), "2000.00");
        InvoiceCharges.ChargeView boleto = invoiceCharges.issue(s.cardId(), "2034-01", "BOLETO", null);

        PayBoletos.Lookup lookup = payBoletos.lookup(boleto.digitableLine());
        assertThat(lookup.kind()).isEqualTo("CARD_INVOICE");
        assertThat(lookup.beneficiaryName()).isEqualTo("Kage (fatura do cartão)");
        assertThat(lookup.description()).isEqualTo("Fatura do cartão Kage de 01/2034");
        assertThat(lookup.amount()).isEqualByComparingTo("1000.00");
        assertThat(lookup.total()).isEqualByComparingTo("1000.00");
        assertThat(lookup.fine()).isEqualByComparingTo("0");
        assertThat(lookup.dueDate()).isEqualTo("2034-01-20");
        assertThat(lookup.payable()).isTrue();

        now(LocalDateTime.of(2034, 1, 15, 9, 0));
        PayBoletos.PaymentView paid = payBoletos.pay(s.accountId(), boleto.barcode(), null);
        assertThat(paid.kind()).isEqualTo("CARD_INVOICE");
        assertThat(paid.paidAmount()).isEqualByComparingTo("1000.00");

        // sem esperar a compensação: a fatura já está paga e o limite voltou
        assertThat(january(s.cardId()).status()).isEqualTo("PAID");
        assertThat(getCard.execute(new GetCard.Input(s.cardId())).availableLimit()).isEqualByComparingTo("5000.00");
        assertThat(invoiceCharges.list(s.cardId(), "2034-01")).singleElement().satisfies(c -> {
            assertThat(c.status()).isEqualTo("PAID");
            assertThat(c.paidAt()).isEqualTo(LocalDateTime.of(2034, 1, 15, 9, 0));
        });

        BoletoReceipts.Receipt receipt = boletoReceipts.of(paid.paymentId());
        assertThat(receipt.beneficiary().name()).isEqualTo("Kage (fatura do cartão)");
        assertThat(receipt.paidAmount()).isEqualByComparingTo("1000.00");

        assertThat(payBoletos.lookup(boleto.barcode())).satisfies(l -> {
            assertThat(l.payable()).isFalse();
            assertThat(l.notPayableReason()).isEqualTo("Boleto já foi pago");
        });
        assertThatThrownBy(() -> payBoletos.pay(s.accountId(), boleto.barcode(), null))
                .isInstanceOf(BusinessRuleException.class).hasMessage("Boleto já foi pago");
    }

    @Test
    void boletoParcial_pagoPorOutraPessoa() {
        Setup s = januaryClosed();
        UUID friend = fundedAccount(newAccountIdFor(newActiveCustomerId()), "500.00");
        InvoiceCharges.ChargeView boleto = invoiceCharges.issue(s.cardId(), "2034-01", "boleto", new BigDecimal("400.00"));

        assertThatThrownBy(() -> payBoletos.pay(friend, boleto.barcode(), new BigDecimal("300.00")))
                .isInstanceOf(ValidationException.class);
        payBoletos.pay(friend, boleto.barcode(), new BigDecimal("400.00"));

        GetInvoice.Output invoice = january(s.cardId());
        assertThat(invoice.status()).isEqualTo("CLOSED");
        assertThat(invoice.remaining()).isEqualByComparingTo("600.00");
    }

    @Test
    void semSaldo_cancelado_eVencido_naoPagam_eNadaMuda() {
        Setup s = januaryClosed();
        InvoiceCharges.ChargeView first = invoiceCharges.issue(s.cardId(), "2034-01", "BOLETO", null);

        // sem saldo: o débito falha e a cobrança continua pendente (tudo na mesma transação)
        assertThatThrownBy(() -> payBoletos.pay(s.accountId(), first.barcode(), null))
                .isInstanceOf(BusinessRuleException.class);
        assertThat(invoiceCharges.list(s.cardId(), "2034-01")).singleElement()
                .satisfies(c -> assertThat(c.status()).isEqualTo("PENDING"));
        assertThat(january(s.cardId()).status()).isEqualTo("CLOSED");

        // um boleto novo cancela o anterior
        fundedAccount(s.accountId(), "2000.00");
        InvoiceCharges.ChargeView second = invoiceCharges.issue(s.cardId(), "2034-01", "BOLETO", null);
        assertThat(payBoletos.lookup(first.barcode()).notPayableReason())
                .isEqualTo("Boleto cancelado: um boleto novo da fatura substituiu este");
        assertThatThrownBy(() -> payBoletos.pay(s.accountId(), first.barcode(), null))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("cancelado");

        // depois do vencimento da fatura, o boleto não vale mais
        now(LocalDateTime.of(2034, 1, 21, 10, 0));
        assertThat(payBoletos.lookup(second.barcode())).satisfies(l -> {
            assertThat(l.payable()).isFalse();
            assertThat(l.notPayableReason()).isEqualTo("Boleto vencido: gere outro na fatura");
        });
        assertThatThrownBy(() -> payBoletos.pay(s.accountId(), second.barcode(), null))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("vencido");
        assertThat(january(s.cardId()).status()).isEqualTo("CLOSED");
    }
}
