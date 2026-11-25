package com.kage.shared.infrastructure.messaging;

import com.kage.account.infrastructure.messaging.DepositMadeEvent;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O OutboxRelay manda o JSON cru, sem o header __TypeId__, e cada contexto tem a sua própria classe
 * de evento (payment.PixSentEvent é publicado, account.PixSentEvent é consumido). Isso só funciona
 * porque o conversor usa o tipo inferido do parâmetro do @RabbitListener; este teste reproduz
 * exatamente o caminho outbox -> relay -> listener sem precisar de broker.
 */
class OutboxMessageFormatTest {

    private final JacksonJsonMessageConverter converter = new JacksonJsonMessageConverter();

    /** Mesma serialização do OutboxEventPublisher + mesma mensagem do OutboxRelay. */
    private Message throughOutbox(Object event, Type listenerParameterType) {
        byte[] payload = converter.toMessage(event, new MessageProperties()).getBody();
        String storedInOutbox = new String(payload, StandardCharsets.UTF_8);

        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding(StandardCharsets.UTF_8.name());
        // o adapter do @RabbitListener preenche isso com o tipo do parâmetro do método
        properties.setInferredArgumentType(listenerParameterType);
        return new Message(storedInOutbox.getBytes(StandardCharsets.UTF_8), properties);
    }

    @Test
    void eventoPublicadoPorUmContexto_deveSerLidoComAClasseDoOutroContexto() {
        UUID transactionId = UUID.randomUUID();
        var published = new com.kage.payment.infrastructure.messaging.PixSentEvent(transactionId, UUID.randomUUID(),
                "chave", UUID.randomUUID(), new BigDecimal("150.00"), "PROCESSING", "E2E1", "2026-11-25T10:00:00");

        Object received = converter.fromMessage(throughOutbox(published,
                com.kage.account.infrastructure.messaging.PixSentEvent.class));

        assertThat(received).isInstanceOf(com.kage.account.infrastructure.messaging.PixSentEvent.class);
        var event = (com.kage.account.infrastructure.messaging.PixSentEvent) received;
        assertThat(event.transactionId()).isEqualTo(transactionId);
        assertThat(event.amount()).isEqualByComparingTo("150.00");
    }

    @Test
    void eventoComInstant_deveSobreviverAoOutbox() {
        Instant occurredAt = Instant.parse("2026-11-25T13:45:10.123Z");
        var published = new DepositMadeEvent(UUID.randomUUID(), new BigDecimal("10.00"), new BigDecimal("110.00"), occurredAt);

        Object received = converter.fromMessage(throughOutbox(published, DepositMadeEvent.class));

        assertThat(received).isEqualTo(published);
    }

    @Test
    void uuidSolto_deveSobreviverAoOutbox() {
        UUID transactionId = UUID.randomUUID();

        Object received = converter.fromMessage(throughOutbox(transactionId, UUID.class));

        assertThat(received).isEqualTo(transactionId);
    }
}
