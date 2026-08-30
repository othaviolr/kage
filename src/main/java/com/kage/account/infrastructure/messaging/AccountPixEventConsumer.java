package com.kage.account.infrastructure.messaging;

import com.kage.account.domain.entity.Account;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.account.infrastructure.persistence.idempotency.ProcessedEventRepository;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.valueobject.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AccountPixEventConsumer {

    private static final Logger logger = LoggerFactory.getLogger(AccountPixEventConsumer.class);

    private final AccountRepository accountRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final RabbitTemplate rabbitTemplate;

    public AccountPixEventConsumer(AccountRepository accountRepository, ProcessedEventRepository processedEventRepository, RabbitTemplate rabbitTemplate) {
        this.accountRepository = accountRepository;
        this.processedEventRepository = processedEventRepository;
        this.rabbitTemplate = rabbitTemplate;
    }

    @Transactional
    @RabbitListener(queues = "pix.sent.queue")
    public void onPixSent(PixSentEvent event) {
        boolean isFirstDelivery = processedEventRepository.tryMarkAsProcessed(event.transactionId());

        if (!isFirstDelivery) {
            logger.warn("Evento PIX já processado, ignorando reentrega (transactionId={})", event.transactionId());
            return;
        }

        Account source = accountRepository.findById(event.sourceAccountId())
                .orElseThrow(() -> new BusinessRuleException("Conta de origem não encontrada"));

        Account target = accountRepository.findById(event.targetAccountId())
                .orElseThrow(() -> new BusinessRuleException("Conta de destino não encontrada"));

        source.debit(new Money(event.amount()));
        target.credit(new Money(event.amount()));

        accountRepository.save(source);
        accountRepository.save(target);

        rabbitTemplate.convertAndSend("pix.exchange", "pix.debit.confirmed", event.transactionId());
    }
}