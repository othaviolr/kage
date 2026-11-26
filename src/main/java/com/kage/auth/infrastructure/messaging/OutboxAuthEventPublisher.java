package com.kage.auth.infrastructure.messaging;

import com.kage.shared.domain.DomainTime;
import com.kage.auth.domain.service.AuthEventPublisher;
import com.kage.shared.infrastructure.messaging.OutboxEventPublisher;

import java.time.Instant;
import java.util.UUID;

public class OutboxAuthEventPublisher implements AuthEventPublisher {

    public static final String EXCHANGE = "auth.exchange";
    public static final String PASSWORD_CHANGED = "auth.password.changed";
    public static final String DEVICE_TRUSTED = "auth.device.trusted";

    private final OutboxEventPublisher outbox;

    public OutboxAuthEventPublisher(OutboxEventPublisher outbox) {
        this.outbox = outbox;
    }

    @Override
    public void publishPasswordChanged(UUID customerId, PasswordChangeReason reason) {
        outbox.publish(EXCHANGE, PASSWORD_CHANGED, new PasswordChangedEvent(customerId, reason.name(), DomainTime.instant()));
    }

    @Override
    public void publishNewDeviceTrusted(UUID customerId, String userAgent) {
        outbox.publish(EXCHANGE, DEVICE_TRUSTED, new DeviceTrustedEvent(customerId, userAgent, DomainTime.instant()));
    }

    public record PasswordChangedEvent(UUID customerId, String reason, Instant occurredAt) {}

    public record DeviceTrustedEvent(UUID customerId, String userAgent, Instant occurredAt) {}
}
