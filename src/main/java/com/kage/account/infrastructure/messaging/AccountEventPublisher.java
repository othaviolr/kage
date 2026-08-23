package com.kage.account.infrastructure.messaging;

import com.kage.account.application.usecase.DepositAccount;
import com.kage.account.application.usecase.WithdrawAccount;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.math.BigDecimal;
import java.time.Instant;

public class AccountEventPublisher {
    private final RabbitTemplate rabbitTemplate;

    public AccountEventPublisher(RabbitTemplate rabbitTemplate){
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publishDepositMade(DepositAccount.Output output, BigDecimal amount) {
        DepositMadeEvent event = new DepositMadeEvent(output.accountId(), amount, output.balance(), Instant.now());
        rabbitTemplate.convertAndSend("account.exchange", "account.deposit.made", event);
    }

    public void publishWithdrawalMade(WithdrawAccount.Output output, BigDecimal amount) {
        WithdrawalMadeEvent event = new WithdrawalMadeEvent(output.accountId(), amount, output.balance(), Instant.now());
        rabbitTemplate.convertAndSend("account.exchange", "account.withdrawal.made", event);
    }
}
