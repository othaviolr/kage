package com.kage.customer.infrastructure.messaging;

import com.kage.shared.domain.DomainTime;
import com.kage.customer.application.usecase.CustomerEventPublisher;
import com.kage.shared.infrastructure.messaging.OutboxEventPublisher;

import java.time.Instant;
import java.util.UUID;

public class OutboxCustomerEventPublisher implements CustomerEventPublisher {

    public static final String EXCHANGE = "customer.exchange";
    public static final String CUSTOMER_BLOCKED = "customer.blocked";

    private final OutboxEventPublisher outbox;

    public OutboxCustomerEventPublisher(OutboxEventPublisher outbox) {
        this.outbox = outbox;
    }

    @Override
    public void publishCustomerBlocked(UUID customerId) {
        outbox.publish(EXCHANGE, CUSTOMER_BLOCKED, new CustomerBlockedEvent(customerId, DomainTime.instant()));
    }

    public record CustomerBlockedEvent(UUID customerId, Instant occurredAt) {}
}
