package com.kage.savings.infrastructure.messaging;

import com.kage.savings.application.usecase.SavingsEventPublisher;
import com.kage.savings.domain.entity.SavingsBox;
import com.kage.shared.infrastructure.messaging.OutboxEventPublisher;

import java.time.Instant;

public class OutboxSavingsEventPublisher implements SavingsEventPublisher {

    private final OutboxEventPublisher outbox;

    public OutboxSavingsEventPublisher(OutboxEventPublisher outbox) {
        this.outbox = outbox;
    }

    @Override
    public void publishGoalReached(SavingsBox box) {
        outbox.publish(SavingsEvents.EXCHANGE, SavingsEvents.GOAL_REACHED, new SavingsEvents.GoalReachedEvent(
                box.getId(), box.getCustomerId(), box.getAccountId(), box.getName(), box.getGoalAmount().amount(),
                box.getBalance().amount(), Instant.now()));
    }
}
