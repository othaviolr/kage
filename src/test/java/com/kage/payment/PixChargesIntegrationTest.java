package com.kage.payment;

import com.kage.AbstractIntegrationTest;
import com.kage.account.application.usecase.DepositAccount;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.notification.application.usecase.ListNotifications;
import com.kage.payment.application.usecase.PixCharges;
import com.kage.payment.application.usecase.PixCopyPaste;
import com.kage.payment.application.usecase.RegisterPixKey;
import com.kage.payment.domain.enums.PixKeyType;
import com.kage.payment.domain.enums.PixTransactionStatus;
import com.kage.payment.domain.repository.PixTransactionRepository;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.exception.ValidationException;
import com.kage.shared.domain.pix.PixBrCode;
import com.kage.shared.domain.DomainTime;
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
 * Cobrar com PIX copia e cola, com Postgres e RabbitMQ reais: quem cobra gera o código, quem paga cola
 * no PIX da conta Kage, e a cobrança mostra quem pagou.
 */
class PixChargesIntegrationTest extends AbstractIntegrationTest {

    @Autowired PixCharges pixCharges;
    @Autowired PixCopyPaste pixCopyPaste;
    @Autowired RegisterPixKey registerPixKey;
    @Autowired DepositAccount depositAccount;
    @Autowired AccountRepository accountRepository;
    @Autowired PixTransactionRepository pixTransactionRepository;
    @Autowired ListNotifications listNotifications;

    private record Receiver(UUID customerId, UUID accountId, String key) {}

    private Receiver receiver() {
        UUID customerId = newActiveCustomerId();
        UUID accountId = newAccountIdFor(customerId);
        String key = "cobra-" + UUID.randomUUID();
        registerPixKey.execute(new RegisterPixKey.Input(accountId, PixKeyType.RANDOM, key));
        return new Receiver(customerId, accountId, key);
    }

    @AfterEach
    void resetClock() {
        DomainTime.reset();
    }

    private static void now(LocalDateTime at) {
        ZoneId zone = ZoneId.systemDefault();
        DomainTime.use(Clock.fixed(at.atZone(zone).toInstant(), zone));
    }

    private UUID payer(String amount) {
        UUID accountId = newAccountIdFor(newActiveCustomerId());
        depositAccount.execute(new DepositAccount.Input(accountId, new BigDecimal(amount)));
        return accountId;
    }

    private void awaitCompleted(UUID transactionId) {
        awaitUntil(() -> pixTransactionRepository.findById(transactionId)
                .map(t -> t.getStatus() == PixTransactionStatus.COMPLETED).orElse(false), Duration.ofSeconds(15));
    }

    private BigDecimal balance(UUID accountId) {
        return accountRepository.findById(accountId).orElseThrow().getBalance().amount();
    }

    @Test
    void cobrancaComValor_usoUnico_pagaUmaVez_eMostraQuemPagou() {
        Receiver r = receiver();
        PixCharges.ChargeView charge = pixCharges.create(r.accountId(), r.key(), new BigDecimal("80"), "Pizza de sexta", null, null);
        assertThat(charge.status()).isEqualTo("OPEN");
        assertThat(charge.singleUse()).as("com valor: uso único por padrão").isTrue();
        assertThat(charge.amount()).isEqualByComparingTo("80.00");
        assertThat(charge.txid()).startsWith("KG").hasSize(25);

        // o código é um BR Code de verdade: qualquer banco leria
        PixBrCode.Parsed parsed = PixBrCode.parse(charge.pixCopyPaste());
        assertThat(parsed.pixKey()).isEqualTo(r.key());
        assertThat(parsed.amount()).isEqualByComparingTo("80.00");
        assertThat(parsed.info()).isEqualTo("Pizza de sexta");
        assertThat(parsed.merchantName()).isEqualTo("Cliente de Teste");
        assertThat(parsed.city()).isEqualTo("Curitiba");
        assertThat(parsed.txid()).isEqualTo(charge.txid());
        assertThat(parsed.singleUse()).isTrue();

        UUID payer = payer("200.00");
        PixCopyPaste.Preview preview = pixCopyPaste.read(charge.pixCopyPaste());
        assertThat(preview.payable()).isTrue();
        assertThat(preview.description()).isEqualTo("Pizza de sexta");
        assertThat(preview.receiverName()).isEqualTo("Cliente de Teste");

        PixCopyPaste.Paid paid = pixCopyPaste.pay(payer, charge.pixCopyPaste(), null, null);
        // a caminho: já ocupa a cobrança de uso único
        assertThat(pixCopyPaste.read(charge.pixCopyPaste()).notPayableReason()).isEqualTo("Cobrança já foi paga");
        awaitCompleted(paid.transactionId());

        PixCharges.ChargeView after = pixCharges.get(charge.id());
        assertThat(after.status()).isEqualTo("PAID");
        assertThat(after.received()).isEqualByComparingTo("80.00");
        assertThat(after.payments()).singleElement().satisfies(p -> {
            assertThat(p.transactionId()).isEqualTo(paid.transactionId());
            assertThat(p.payerName()).isEqualTo("Cliente de Teste");
            assertThat(p.amount()).isEqualByComparingTo("80.00");
            assertThat(p.status()).isEqualTo("COMPLETED");
        });
        assertThat(balance(r.accountId())).isEqualByComparingTo("80.00");

        // quem cobrou é avisado quando o PIX conclui
        awaitUntil(() -> listNotifications.execute(new ListNotifications.Input(r.customerId(), false)).notifications()
                .stream().anyMatch(n -> n.type().equals("PIX_CHARGE_PAID")), Duration.ofSeconds(15));
        assertThat(listNotifications.execute(new ListNotifications.Input(r.customerId(), false)).notifications())
                .filteredOn(n -> n.type().equals("PIX_CHARGE_PAID")).singleElement().satisfies(n -> {
                    assertThat(n.title()).isEqualTo("Cobrança paga");
                    assertThat(n.message()).isEqualTo("Cliente de Teste pagou R$ 80,00 de sua cobrança \"Pizza de sexta\".");
                    assertThat(n.referenceId()).isEqualTo(charge.id());
                });

        UUID other = payer("200.00");
        assertThatThrownBy(() -> pixCopyPaste.pay(other, charge.pixCopyPaste(), null, null))
                .isInstanceOf(BusinessRuleException.class).hasMessage("Cobrança já foi paga");
        assertThat(balance(other)).isEqualByComparingTo("200.00");
        assertThatThrownBy(() -> pixCharges.cancel(charge.id()))
                .isInstanceOf(BusinessRuleException.class).hasMessage("A cobrança já foi paga");
        assertThat(pixCharges.list(r.accountId())).extracting(PixCharges.ChargeView::id).containsExactly(charge.id());
    }

    @Test
    void cobrancaEmAberto_variosPagadores_eCancelamento() {
        Receiver r = receiver();
        PixCharges.ChargeView charge = pixCharges.create(r.accountId(), r.key(), null, "Vaquinha da Ana", null, null);
        assertThat(charge.amountOpen()).isTrue();
        assertThat(charge.singleUse()).as("sem valor: aberto por padrão").isFalse();
        assertThat(pixCopyPaste.read(charge.pixCopyPaste()).amountRequired()).isTrue();

        PixCopyPaste.Paid first = pixCopyPaste.pay(payer("100.00"), charge.pixCopyPaste(), new BigDecimal("10.00"), null);
        PixCopyPaste.Paid second = pixCopyPaste.pay(payer("100.00"), charge.pixCopyPaste(), new BigDecimal("25.50"), null);
        awaitCompleted(first.transactionId());
        awaitCompleted(second.transactionId());

        PixCharges.ChargeView after = pixCharges.get(charge.id());
        assertThat(after.status()).isEqualTo("OPEN");
        assertThat(after.paymentCount()).isEqualTo(2);
        assertThat(after.received()).isEqualByComparingTo("35.50");

        PixCharges.ChargeView cancelled = pixCharges.cancel(charge.id());
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.cancelledAt()).isNotNull();
        assertThat(pixCopyPaste.read(charge.pixCopyPaste()).notPayableReason())
                .isEqualTo("Cobrança cancelada por quem está cobrando");
        assertThatThrownBy(() -> pixCopyPaste.pay(payer("100.00"), charge.pixCopyPaste(), BigDecimal.ONE, null))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("cancelada");
        assertThatThrownBy(() -> pixCharges.cancel(charge.id())).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void validade_venceNoFimDoUltimoDia() {
        now(LocalDateTime.of(2034, 3, 10, 10, 0));
        Receiver r = receiver();
        assertThatThrownBy(() -> pixCharges.create(r.accountId(), r.key(), BigDecimal.TEN, null, null, 0))
                .isInstanceOf(ValidationException.class).hasMessage("Validade de 1 a 365 dias");
        assertThatThrownBy(() -> pixCharges.create(r.accountId(), r.key(), BigDecimal.TEN, null, null, 366))
                .isInstanceOf(ValidationException.class);

        PixCharges.ChargeView charge = pixCharges.create(r.accountId(), r.key(), new BigDecimal("40.00"), "Ingresso", null, 2);
        assertThat(charge.expiresAt()).isEqualTo(LocalDateTime.of(2034, 3, 12, 23, 59, 59));
        UUID payer = payer("100.00");

        now(LocalDateTime.of(2034, 3, 12, 23, 59, 0));
        assertThat(pixCharges.get(charge.id()).status()).isEqualTo("OPEN");
        assertThat(pixCopyPaste.read(charge.pixCopyPaste()).payable()).isTrue();

        now(LocalDateTime.of(2034, 3, 13, 0, 0));
        assertThat(pixCharges.get(charge.id()).status()).isEqualTo("EXPIRED");
        assertThat(pixCopyPaste.read(charge.pixCopyPaste()).notPayableReason()).isEqualTo("Cobrança venceu em 12/03/2034");
        assertThatThrownBy(() -> pixCopyPaste.pay(payer, charge.pixCopyPaste(), null, null))
                .isInstanceOf(BusinessRuleException.class).hasMessage("Cobrança venceu em 12/03/2034");
        assertThat(balance(payer)).isEqualByComparingTo("100.00");

        // vencida ainda pode ser cancelada (some da lista de abertas de quem cobrou)
        assertThat(pixCharges.cancel(charge.id()).status()).isEqualTo("CANCELLED");
    }

    @Test
    void validacoes_eCodigoAlterado() {
        Receiver r = receiver();
        Receiver someoneElse = receiver();
        assertThatThrownBy(() -> pixCharges.create(r.accountId(), someoneElse.key(), BigDecimal.TEN, null, null, null))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> pixCharges.create(r.accountId(), r.key(), BigDecimal.ZERO, null, null, null))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> pixCharges.create(r.accountId(), r.key(), new BigDecimal("1.234"), null, null, null))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> pixCharges.create(r.accountId(), r.key(), BigDecimal.TEN, "x".repeat(60), null, null))
                .isInstanceOf(ValidationException.class).hasMessageContaining("caracteres para esta chave");

        PixCharges.ChargeView charge = pixCharges.create(r.accountId(), r.key(), new BigDecimal("50.00"), null, false, null);
        assertThat(charge.singleUse()).isFalse();
        // mesmo txid e chave, valor trocado (com CRC refeito): o Kage não aceita
        String forged = PixBrCode.of(r.key(), new BigDecimal("5.00"), "Cliente de Teste", "Curitiba", charge.txid(), false);
        assertThat(pixCopyPaste.read(forged).notPayableReason()).startsWith("Código alterado");
        assertThatThrownBy(() -> pixCopyPaste.pay(payer("100.00"), forged, null, null))
                .isInstanceOf(BusinessRuleException.class).hasMessageStartingWith("Código alterado");
    }
}
