package com.kage.payment;

import com.kage.AbstractIntegrationTest;
import com.kage.account.application.usecase.DepositAccount;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.card.application.usecase.CloseInvoice;
import com.kage.card.application.usecase.GetCard;
import com.kage.card.application.usecase.GetInvoice;
import com.kage.card.application.usecase.InvoiceCharges;
import com.kage.card.application.usecase.IssueCard;
import com.kage.card.application.usecase.RegisterPurchase;
import com.kage.payment.application.usecase.PixCopyPaste;
import com.kage.payment.application.usecase.RegisterPixKey;
import com.kage.payment.application.usecase.SendPix;
import com.kage.payment.domain.enums.PixKeyType;
import com.kage.payment.domain.enums.PixTransactionStatus;
import com.kage.payment.domain.repository.PixTransactionRepository;
import com.kage.shared.domain.DomainTime;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.exception.ValidationException;
import com.kage.shared.domain.pix.PixBrCode;
import com.kage.shared.domain.valueobject.Money;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PIX copia e cola no PIX da conta Kage, com Postgres e RabbitMQ reais: o PIX comum para uma chave de
 * cliente Kage, e o PIX da fatura do cartão, pago na mesma transação do débito.
 */
class PixCopyPasteIntegrationTest extends AbstractIntegrationTest {

    @Autowired PixCopyPaste pixCopyPaste;
    @Autowired RegisterPixKey registerPixKey;
    @Autowired SendPix sendPix;
    @Autowired DepositAccount depositAccount;
    @Autowired AccountRepository accountRepository;
    @Autowired PixTransactionRepository pixTransactionRepository;
    @Autowired IssueCard issueCard;
    @Autowired RegisterPurchase registerPurchase;
    @Autowired CloseInvoice closeInvoice;
    @Autowired GetInvoice getInvoice;
    @Autowired GetCard getCard;
    @Autowired InvoiceCharges invoiceCharges;

    @AfterEach
    void resetClock() {
        DomainTime.reset();
    }

    private static void now(LocalDateTime at) {
        ZoneId zone = ZoneId.systemDefault();
        DomainTime.use(Clock.fixed(at.atZone(zone).toInstant(), zone));
    }

    private UUID fundedAccount(String amount) {
        UUID accountId = newAccountIdFor(newActiveCustomerId());
        depositAccount.execute(new DepositAccount.Input(accountId, new BigDecimal(amount)));
        return accountId;
    }

    private BigDecimal balance(UUID accountId) {
        return accountRepository.findById(accountId).orElseThrow().getBalance().amount();
    }

    @Test
    void chaveDeClienteKage_comValorNoCodigo_viraUmPixComum() {
        UUID payer = fundedAccount("500.00");
        UUID receiver = newAccountIdFor(newActiveCustomerId());
        String key = "chave-" + UUID.randomUUID();
        registerPixKey.execute(new RegisterPixKey.Input(receiver, PixKeyType.RANDOM, key));
        String code = PixBrCode.of(key, new BigDecimal("150.00"), "Fulana Loja", "Sao Paulo", "PEDIDO42", false);

        PixCopyPaste.Preview preview = pixCopyPaste.read(code);
        assertThat(preview.kind()).isEqualTo("PIX_KEY");
        assertThat(preview.pixKey()).isEqualTo(key);
        assertThat(preview.receiverName()).isNotBlank();
        assertThat(preview.receiverDocument()).startsWith("***.");
        assertThat(preview.merchantName()).isEqualTo("Fulana Loja");
        assertThat(preview.amount()).isEqualByComparingTo("150.00");
        assertThat(preview.amountRequired()).isFalse();
        assertThat(preview.txid()).isEqualTo("PEDIDO42");
        assertThat(preview.payable()).isTrue();

        assertThatThrownBy(() -> pixCopyPaste.pay(payer, code, new BigDecimal("100.00"), null))
                .isInstanceOf(ValidationException.class).hasMessageContaining("fixo");

        PixCopyPaste.Paid paid = pixCopyPaste.pay(payer, code, null, "Pedido 42");
        assertThat(paid.kind()).isEqualTo("PIX_KEY");
        assertThat(paid.transactionId()).isNotNull();
        assertThat(paid.amount()).isEqualByComparingTo("150.00");

        awaitUntil(() -> pixTransactionRepository.findById(paid.transactionId())
                .map(t -> t.getStatus() == PixTransactionStatus.COMPLETED).orElse(false), Duration.ofSeconds(15));
        assertThat(balance(payer)).isEqualByComparingTo("350.00");
        assertThat(balance(receiver)).isEqualByComparingTo("150.00");
    }

    @Test
    void codigoSemValor_pagadorDigita_eChaveDeFora_naoPaga() {
        UUID payer = fundedAccount("500.00");
        UUID receiver = newAccountIdFor(newActiveCustomerId());
        String key = "chave-" + UUID.randomUUID();
        registerPixKey.execute(new RegisterPixKey.Input(receiver, PixKeyType.RANDOM, key));
        String open = PixBrCode.of(key, null, "Fulana", "Rio", null, false);

        assertThat(pixCopyPaste.read(open).amountRequired()).isTrue();
        assertThatThrownBy(() -> pixCopyPaste.pay(payer, open, null, null))
                .isInstanceOf(ValidationException.class).hasMessageContaining("informe quanto");
        PixCopyPaste.Paid paid = pixCopyPaste.pay(payer, open, new BigDecimal("20.00"), null);
        assertThat(paid.amount()).isEqualByComparingTo("20.00");

        String outside = PixBrCode.of("alguem@outrobanco.com", BigDecimal.TEN, "Outro", "SP", null, false);
        PixCopyPaste.Preview preview = pixCopyPaste.read(outside);
        assertThat(preview.payable()).isFalse();
        assertThat(preview.notPayableReason()).contains("não encontrada no Kage");
        assertThatThrownBy(() -> pixCopyPaste.pay(payer, outside, null, null)).isInstanceOf(NotFoundException.class);
    }

    private record Setup(UUID cardId, UUID accountId) {}

    /** Cartão de 5.000 que fecha dia 10 e vence dia 20; compra de 600 em janeiro/2034, fatura fechada em 11/01. */
    private Setup januaryClosed() {
        now(LocalDateTime.of(2034, 1, 5, 10, 0));
        IssueCard.Input input = issueCardInput(new BigDecimal("5000.00"), 10, 20);
        UUID cardId = issueCard.execute(input).cardId();
        registerPurchase.execute(new RegisterPurchase.Input(cardId, UUID.randomUUID(), "Mercado", new BigDecimal("600.00"),
                LocalDateTime.of(2034, 1, 5, 10, 0)));
        now(LocalDateTime.of(2034, 1, 11, 10, 0));
        closeInvoice.execute(new CloseInvoice.Input(cardId, "2034-01"));
        return new Setup(cardId, input.accountId());
    }

    @Test
    void pixDaFatura_pagoPeloPixDaConta_quitaNaHora_eContaNoLimite() {
        Setup s = januaryClosed();
        depositAccount.execute(new DepositAccount.Input(s.accountId(), new BigDecimal("3000.00")));
        InvoiceCharges.ChargeView pix = invoiceCharges.issue(s.cardId(), "2034-01", "PIX", null);

        PixCopyPaste.Preview preview = pixCopyPaste.read(pix.pixCopyPaste());
        assertThat(preview.kind()).isEqualTo("CARD_INVOICE");
        assertThat(preview.receiverName()).isEqualTo("Kage (fatura do cartão)");
        assertThat(preview.description()).isEqualTo("Fatura do cartão Kage de 01/2034");
        assertThat(preview.amount()).isEqualByComparingTo("600.00");
        assertThat(preview.singleUse()).isTrue();
        assertThat(preview.expiresAt()).isEqualTo(LocalDateTime.of(2034, 1, 12, 10, 0));
        assertThat(preview.payable()).isTrue();

        now(LocalDateTime.of(2034, 1, 11, 15, 0));
        PixCopyPaste.Paid paid = pixCopyPaste.pay(s.accountId(), pix.pixCopyPaste(), null, null);
        assertThat(paid.kind()).isEqualTo("CARD_INVOICE");
        assertThat(paid.status()).isEqualTo("COMPLETED");
        assertThat(paid.paymentId()).isNotNull();
        assertThat(paid.amount()).isEqualByComparingTo("600.00");

        // sem esperar a liquidação: fatura paga, limite de volta, conta debitada
        assertThat(getInvoice.execute(new GetInvoice.Input(s.cardId(), "2034-01")).status()).isEqualTo("PAID");
        assertThat(getCard.execute(new GetCard.Input(s.cardId())).availableLimit()).isEqualByComparingTo("5000.00");
        assertThat(balance(s.accountId())).isEqualByComparingTo("2400.00");
        assertThat(invoiceCharges.list(s.cardId(), "2034-01")).singleElement()
                .satisfies(c -> assertThat(c.status()).isEqualTo("PAID"));
        assertThat(pixCopyPaste.payment(paid.paymentId())).satisfies(p -> {
            assertThat(p.accountId()).isEqualTo(s.accountId());
            assertThat(p.beneficiary()).isEqualTo("Kage (fatura do cartão)");
            assertThat(p.amount()).isEqualByComparingTo("600.00");
        });

        // uma vez só
        assertThat(pixCopyPaste.read(pix.pixCopyPaste()).notPayableReason()).isEqualTo("PIX já foi pago");
        assertThatThrownBy(() -> pixCopyPaste.pay(s.accountId(), pix.pixCopyPaste(), null, null))
                .isInstanceOf(BusinessRuleException.class).hasMessage("PIX já foi pago");

        // os 600 entram no limite de PIX do dia (2.000): sobram 1.400 para os PIX comuns
        UUID receiver = newAccountIdFor(newActiveCustomerId());
        String key = "chave-" + UUID.randomUUID();
        registerPixKey.execute(new RegisterPixKey.Input(receiver, PixKeyType.RANDOM, key));
        assertThatThrownBy(() -> sendPix.execute(new SendPix.Input(s.accountId(), key, Money.of("1500.00"), null)))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void pixDaFatura_semSaldo_cancelado_vencido_eDesconhecido() {
        Setup s = januaryClosed();
        InvoiceCharges.ChargeView first = invoiceCharges.issue(s.cardId(), "2034-01", "PIX", null);

        // sem saldo: nada muda, a cobrança continua pendente
        assertThatThrownBy(() -> pixCopyPaste.pay(s.accountId(), first.pixCopyPaste(), null, null))
                .isInstanceOf(BusinessRuleException.class);
        assertThat(invoiceCharges.list(s.cardId(), "2034-01")).singleElement()
                .satisfies(c -> assertThat(c.status()).isEqualTo("PENDING"));

        // outra pessoa paga, mas não pode mudar o valor
        UUID friend = fundedAccount("1000.00");
        assertThatThrownBy(() -> pixCopyPaste.pay(friend, first.pixCopyPaste(), new BigDecimal("100.00"), null))
                .isInstanceOf(ValidationException.class).hasMessageContaining("fixo");

        // um código novo cancela o anterior
        InvoiceCharges.ChargeView second = invoiceCharges.issue(s.cardId(), "2034-01", "PIX", new BigDecimal("200.00"));
        assertThat(pixCopyPaste.read(first.pixCopyPaste()).notPayableReason())
                .isEqualTo("PIX cancelado: um código novo da fatura substituiu este");
        assertThatThrownBy(() -> pixCopyPaste.pay(friend, first.pixCopyPaste(), null, null))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("cancelado");

        // depois de 24 horas o PIX não vale mais
        now(LocalDateTime.of(2034, 1, 12, 10, 1));
        assertThat(pixCopyPaste.read(second.pixCopyPaste()).notPayableReason()).isEqualTo("PIX vencido: gere outro na fatura");
        assertThatThrownBy(() -> pixCopyPaste.pay(friend, second.pixCopyPaste(), null, null))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("vencido");
        assertThat(balance(friend)).isEqualByComparingTo("1000.00");

        // chave do Kage, mas código que o Kage não gerou
        String forged = PixBrCode.of(invoiceCharges.pixKey(), new BigDecimal("1.00"), "KAGE", "SAO PAULO", "FATXYZ", true);
        assertThat(pixCopyPaste.read(forged).payable()).isFalse();
        assertThatThrownBy(() -> pixCopyPaste.pay(friend, forged, null, null)).isInstanceOf(NotFoundException.class);
    }
}
