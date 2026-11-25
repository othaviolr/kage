package com.kage.notification.infrastructure.messaging;

import com.kage.account.infrastructure.messaging.AccountPixFailedEvent;
import com.kage.account.infrastructure.messaging.AccountPixMovementEvent;
import com.kage.account.infrastructure.messaging.DepositMadeEvent;
import com.kage.card.infrastructure.messaging.CardEvents;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contrato produtor -> notificação: o evento que Account/Card publicam (pelo outbox, sem header de
 * tipo) precisa ser lido no record de entrada da notificação — que ignora de propósito os campos
 * que não usa (ex.: newBalance do depósito).
 */
class InboundEventsContractTest {

    private final JacksonJsonMessageConverter converter = new JacksonJsonMessageConverter();

    @SuppressWarnings("unchecked")
    private <T> T through(Object published, Class<T> consumedAs) {
        byte[] body = converter.toMessage(published, new MessageProperties()).getBody();
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setContentEncoding(StandardCharsets.UTF_8.name());
        props.setInferredArgumentType(consumedAs);
        return (T) converter.fromMessage(new Message(body, props));
    }

    @Test
    void deposito_comCampoQueANotificacaoNaoUsa() {
        UUID accountId = UUID.randomUUID();
        var in = through(new DepositMadeEvent(accountId, new BigDecimal("50.00"), new BigDecimal("150.00"), Instant.now()),
                InboundEvents.AccountMovement.class);

        assertThat(in.accountId()).isEqualTo(accountId);
        assertThat(in.amount()).isEqualByComparingTo("50.00");
    }

    @Test
    void movimentoEFalhaDePix() {
        UUID ref = UUID.randomUUID();
        var movement = through(new AccountPixMovementEvent(UUID.randomUUID(), ref, new BigDecimal("10.00"), Instant.now()),
                InboundEvents.AccountPixMovement.class);
        var failed = through(new AccountPixFailedEvent(UUID.randomUUID(), ref, new BigDecimal("10.00"), "Saldo disponível insuficiente", Instant.now()),
                InboundEvents.AccountPixFailed.class);

        assertThat(movement.referenceId()).isEqualTo(ref);
        assertThat(failed.reason()).isEqualTo("Saldo disponível insuficiente");
    }

    @Test
    void eventosDeFatura() {
        UUID cardId = UUID.randomUUID();
        var closed = through(new CardEvents.InvoiceClosedEvent(cardId, UUID.randomUUID(), "2026-11", new BigDecimal("1500.00"),
                LocalDate.of(2026, 11, 20), Instant.now()), InboundEvents.InvoiceClosed.class);
        var paid = through(new CardEvents.InvoicePaidEvent(cardId, UUID.randomUUID(), "2026-11", new BigDecimal("1500.00"), Instant.now()),
                InboundEvents.InvoicePaid.class);
        var failed = through(new CardEvents.InvoiceAutoDebitFailedEvent(cardId, "2026-11", "Saldo disponível insuficiente", Instant.now()),
                InboundEvents.InvoiceAutoDebitFailed.class);

        assertThat(closed.dueDate()).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(paid.amount()).isEqualByComparingTo("1500.00");
        assertThat(failed.cardId()).isEqualTo(cardId);
    }
}
