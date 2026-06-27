package com.kage.account.infrastructure.messaging;

import com.kage.account.domain.entity.Account;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.shared.domain.exception.DomainException;
import com.kage.shared.domain.valueobject.Money;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class AccountPixEventConsumer {

    private final AccountRepository accountRepository;
    private final RabbitTemplate rabbitTemplate;

    public AccountPixEventConsumer(AccountRepository accountRepository, RabbitTemplate rabbitTemplate) {
        this.accountRepository = accountRepository;
        this.rabbitTemplate = rabbitTemplate;
    }

    @RabbitListener(queues = "pix.sent.queue")
    public void onPixSent(PixSentEvent event) {
        Account account = accountRepository.findById(event.sourceAccountId())
                .orElseThrow(() -> new DomainException("Conta de origem não encontrada"));

        account.debit(new Money(event.amount()));

        accountRepository.save(account);

        rabbitTemplate.convertAndSend("pix.exchange", "pix.debit.confirmed", event.transactionId());
    }
}