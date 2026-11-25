package com.kage.account.infrastructure.messaging;

import com.kage.account.domain.entity.Account;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.account.infrastructure.persistence.idempotency.ProcessedEventRepository;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.valueobject.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import com.kage.shared.infrastructure.messaging.OutboxEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AccountPixEventConsumer {

    private static final Logger logger = LoggerFactory.getLogger(AccountPixEventConsumer.class);

    private final AccountRepository accountRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final OutboxEventPublisher outbox;

    public AccountPixEventConsumer(AccountRepository accountRepository, ProcessedEventRepository processedEventRepository, OutboxEventPublisher outbox) {
        this.accountRepository = accountRepository;
        this.processedEventRepository = processedEventRepository;
        this.outbox = outbox;
    }

    @Transactional
    @RabbitListener(queues = "pix.sent.queue")
    public void onPixSent(PixSentEvent event) {
        boolean isFirstDelivery = processedEventRepository.tryMarkAsProcessed(event.transactionId());

        if (!isFirstDelivery) {
            logger.warn("Evento PIX já processado, ignorando reentrega (transactionId={})", event.transactionId());
            return;
        }

        try {
            Account source = accountRepository.findById(event.sourceAccountId())
                    .orElseThrow(() -> new BusinessRuleException("Conta de origem não encontrada"));

            Account target = accountRepository.findById(event.targetAccountId())
                    .orElseThrow(() -> new BusinessRuleException("Conta de destino não encontrada"));

            source.debit(new Money(event.amount()));
            target.credit(new Money(event.amount()));

            accountRepository.save(source);
            accountRepository.save(target);
        } catch (BusinessRuleException e) {
            // regra de negócio violada não melhora com retry: avisa o Payment para marcar o PIX como FAILED
            // em vez de deixar a mensagem ir pra DLQ e a transação presa em PROCESSING. Nada foi salvo, e o
            // evento continua marcado como processado (a decisão sobre ele já foi tomada).
            logger.warn("Falha ao efetivar PIX (transactionId={}): {}", event.transactionId(), e.getMessage());
            outbox.publish("pix.exchange", "pix.debit.failed",
                    new PixDebitFailedEvent(event.transactionId(), e.getMessage()));
            return;
        }

        outbox.publish("pix.exchange", "pix.debit.confirmed", event.transactionId());
    }
}