package com.kage.account.infrastructure.messaging;

import com.kage.account.domain.entity.Account;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.shared.domain.exception.BusinessRuleException;
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
        Account source = accountRepository.findById(event.sourceAccountId()).orElseThrow(() -> new BusinessRuleException("Conta de origem não encontrada"));

        Account target = accountRepository.findById(event.targetAccountId()).orElseThrow(() -> new BusinessRuleException("Conta de destino não encontrada"));

        source.debit(new Money(event.amount()));
        target.credit(new Money(event.amount()));

        accountRepository.save(source);
        accountRepository.save(target);

        rabbitTemplate.convertAndSend("pix.exchange", "pix.debit.confirmed", event.transactionId());
    }
}