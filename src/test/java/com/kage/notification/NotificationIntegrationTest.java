package com.kage.notification;

import com.kage.AbstractIntegrationTest;
import com.kage.account.application.usecase.CreateAccount;
import com.kage.account.application.usecase.DepositAccount;
import com.kage.card.application.usecase.CloseInvoice;
import com.kage.card.application.usecase.IssueCard;
import com.kage.card.application.usecase.RegisterPurchase;
import com.kage.notification.application.usecase.ListNotifications;
import com.kage.payment.application.usecase.RegisterPixKey;
import com.kage.payment.application.usecase.SendPix;
import com.kage.payment.domain.enums.PixKeyType;
import com.kage.shared.domain.valueobject.Money;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Do evento de negócio ao aviso na caixa de entrada, com Postgres e RabbitMQ reais:
 * use case -> outbox -> relay -> exchange do produtor -> fila da notificação -> notifications.
 */
class NotificationIntegrationTest extends AbstractIntegrationTest {

    @Autowired CreateAccount createAccount;
    @Autowired DepositAccount depositAccount;
    @Autowired RegisterPixKey registerPixKey;
    @Autowired SendPix sendPix;
    @Autowired IssueCard issueCard;
    @Autowired RegisterPurchase registerPurchase;
    @Autowired CloseInvoice closeInvoice;
    @Autowired ListNotifications listNotifications;
    @Autowired RabbitTemplate rabbitTemplate;

    private List<ListNotifications.Item> inbox(UUID customerId) {
        return listNotifications.execute(new ListNotifications.Input(customerId, false)).notifications();
    }

    private void awaitNotification(UUID customerId, String type) {
        awaitUntil(() -> inbox(customerId).stream().anyMatch(n -> n.type().equals(type)), Duration.ofSeconds(15));
    }

    @Test
    void deposito_deveAvisarODonoDaConta() {
        UUID customerId = newActiveCustomerId();
        UUID accountId = newAccountIdFor(customerId);

        depositAccount.execute(new DepositAccount.Input(accountId, new BigDecimal("250.00")));

        awaitNotification(customerId, "DEPOSIT_RECEIVED");
        var notification = inbox(customerId).get(0);
        assertThat(notification.title()).isEqualTo("Depósito recebido");
        assertThat(notification.message()).contains("R$ 250,00");
        assertThat(notification.read()).isFalse();
        assertThat(listNotifications.execute(new ListNotifications.Input(customerId, true)).unreadCount()).isEqualTo(1);
    }

    @Test
    void pix_deveAvisarQuemEnviouEQuemRecebeu() {
        UUID payer = newActiveCustomerId();
        UUID receiver = newActiveCustomerId();
        UUID payerAccount = newAccountIdFor(payer);
        UUID receiverAccount = newAccountIdFor(receiver);
        depositAccount.execute(new DepositAccount.Input(payerAccount, new BigDecimal("500.00")));
        String key = "chave-" + UUID.randomUUID();
        registerPixKey.execute(new RegisterPixKey.Input(receiverAccount, PixKeyType.RANDOM, key));

        SendPix.Output pix = sendPix.execute(new SendPix.Input(payerAccount, key, Money.of("120.00"), "almoço"));

        awaitNotification(payer, "PIX_SENT");
        awaitNotification(receiver, "PIX_RECEIVED");
        assertThat(inbox(receiver)).anySatisfy(n -> {
            assertThat(n.type()).isEqualTo("PIX_RECEIVED");
            assertThat(n.message()).contains("R$ 120,00");
            assertThat(n.referenceId()).isEqualTo(pix.transactionId());
        });
    }

    @Test
    void faturaFechada_deveAvisarODonoDoCartao() {
        IssueCard.Input input = issueCardInput(new BigDecimal("1000.00"), 10, 20);
        UUID cardId = issueCard.execute(input).cardId();
        registerPurchase.execute(new RegisterPurchase.Input(cardId, UUID.randomUUID(), "Mercado", new BigDecimal("80.00"),
                LocalDateTime.of(2032, 5, 3, 10, 0)));

        closeInvoice.execute(new CloseInvoice.Input(cardId, "2032-05"));

        awaitNotification(input.customerId(), "INVOICE_CLOSED");
        assertThat(inbox(input.customerId())).anySatisfy(n ->
                assertThat(n.message()).isEqualTo("A fatura de 05/2032 fechou em R$ 80,00 e vence em 20/05."));
    }

    @Test
    void reentregaDaMesmaMensagem_naoDeveDuplicarOAviso() {
        UUID customerId = newActiveCustomerId();
        UUID accountId = newAccountIdFor(customerId);
        String json = "{\"accountId\":\"" + accountId + "\",\"amount\":10.00,\"newBalance\":10.00,\"occurredAt\":\"2026-11-25T10:00:00Z\"}";
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setMessageId("evento-repetido-" + UUID.randomUUID());
        Message message = new Message(json.getBytes(StandardCharsets.UTF_8), props);

        rabbitTemplate.send("account.exchange", "account.deposit.made", message);
        rabbitTemplate.send("account.exchange", "account.deposit.made", message); // reentrega

        awaitNotification(customerId, "DEPOSIT_RECEIVED");
        sleep(Duration.ofSeconds(2)); // tempo para a segunda entrega ser processada (e ignorada)
        assertThat(inbox(customerId)).hasSize(1);
    }
}
