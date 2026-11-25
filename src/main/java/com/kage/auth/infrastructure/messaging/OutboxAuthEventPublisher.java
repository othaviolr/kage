package com.kage.auth.infrastructure.messaging;

import com.kage.auth.domain.service.AuthEventPublisher;
import com.kage.shared.infrastructure.messaging.OutboxEventPublisher;

import java.time.Instant;
import java.util.UUID;

public class OutboxAuthEventPublisher implements AuthEventPublisher {

    public static final String EXCHANGE = "auth.exchange";
    public static final String PASSWORD_CHANGED = "auth.password.changed";

    private final OutboxEventPublisher outbox;

    public OutboxAuthEventPublisher(OutboxEventPublisher outbox) {
        this.outbox = outbox;
    }

    @Override
    public void publishPasswordChanged(UUID customerId, PasswordChangeReason reason) {
        outbox.publish(EXCHANGE, PASSWORD_CHANGED, new PasswordChangedEvent(customerId, reason.name(), Instant.now()));
    }

    public record PasswordChangedEvent(UUID customerId, String reason, Instant occurredAt) {}
}
