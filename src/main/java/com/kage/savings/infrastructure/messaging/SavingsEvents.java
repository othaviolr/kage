package com.kage.savings.infrastructure.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Eventos publicados no savings.exchange. */
public final class SavingsEvents {

    private SavingsEvents() {
    }

    public static final String EXCHANGE = "savings.exchange";
    public static final String GOAL_REACHED = "savings.goal.reached";

    public record GoalReachedEvent(UUID boxId, UUID customerId, UUID accountId, String name, BigDecimal goalAmount,
                                   BigDecimal balance, Instant occurredAt) {}
}
