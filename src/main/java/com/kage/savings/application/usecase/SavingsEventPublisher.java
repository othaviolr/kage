package com.kage.savings.application.usecase;

import com.kage.savings.domain.entity.SavingsBox;

/** Eventos das caixinhas. Grava no outbox: chamar dentro da transação. */
public interface SavingsEventPublisher {

    void publishGoalReached(SavingsBox box);
}
