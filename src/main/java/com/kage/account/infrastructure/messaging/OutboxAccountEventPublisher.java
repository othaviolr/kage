package com.kage.account.infrastructure.messaging;

import com.kage.account.application.usecase.AccountEventPublisher;
import com.kage.shared.infrastructure.messaging.OutboxEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public class OutboxAccountEventPublisher implements AccountEventPublisher {

    private final OutboxEventPublisher outbox;

    public OutboxAccountEventPublisher(OutboxEventPublisher outbox) {
        this.outbox = outbox;
    }

    @Override
    public void publishDepositMade(UUID accountId, BigDecimal amount, BigDecimal newBalance) {
        outbox.publish("account.exchange", "account.deposit.made",
                new DepositMadeEvent(accountId, amount, newBalance, Instant.now()));
    }

    @Override
    public void publishWithdrawalMade(UUID accountId, BigDecimal amount, BigDecimal newBalance) {
        outbox.publish("account.exchange", "account.withdrawal.made",
                new WithdrawalMadeEvent(accountId, amount, newBalance, Instant.now()));
    }
}
