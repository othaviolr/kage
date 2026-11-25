package com.kage.account.infrastructure.messaging;

import com.kage.account.domain.entity.Account;
import com.kage.account.domain.entity.AccountEntry;
import com.kage.account.domain.enums.EntryType;
import com.kage.account.domain.repository.AccountEntryRepository;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.account.infrastructure.persistence.idempotency.ProcessedEventRepository;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.valueobject.Money;
import com.kage.shared.infrastructure.messaging.OutboxEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Component
public class AccountPixEventConsumer {

    private static final Logger logger = LoggerFactory.getLogger(AccountPixEventConsumer.class);
    private static final String ACCOUNT_EXCHANGE = "account.exchange";

    private final AccountRepository accountRepository;
    private final AccountEntryRepository entryRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final OutboxEventPublisher outbox;

    public AccountPixEventConsumer(AccountRepository accountRepository, AccountEntryRepository entryRepository,
                                   ProcessedEventRepository processedEventRepository, OutboxEventPublisher outbox) {
        this.accountRepository = accountRepository;
        this.entryRepository = entryRepository;
        this.processedEventRepository = processedEventRepository;
        this.outbox = outbox;
    }

    @Transactional
    @RabbitListener(queues = "pix.sent.queue")
    public void onPixSent(PixSentEvent event) {
        if (!processedEventRepository.tryMarkAsProcessed(event.transactionId())) {
            logger.warn("Evento PIX já processado, ignorando reentrega (transactionId={})", event.transactionId());
            return;
        }

        Optional<String> failure = transfer(event.sourceAccountId(), event.targetAccountId(), new Money(event.amount()),
                EntryType.PIX_SENT, EntryType.PIX_RECEIVED, event.transactionId());

        if (failure.isPresent()) {
            logger.warn("Falha ao efetivar PIX (transactionId={}): {}", event.transactionId(), failure.get());
            outbox.publish("pix.exchange", "pix.debit.failed", new PixDebitFailedEvent(event.transactionId(), failure.get()));
            outbox.publish(ACCOUNT_EXCHANGE, "account.pix.failed", new AccountPixFailedEvent(event.sourceAccountId(),
                    event.transactionId(), event.amount(), failure.get(), Instant.now()));
            return;
        }

        outbox.publish("pix.exchange", "pix.debit.confirmed", event.transactionId());
        publishMovements(event.sourceAccountId(), event.targetAccountId(), event.transactionId(), event.amount(),
                "account.pix.sent", "account.pix.received");
    }

    /** Estorno aprovado: o dinheiro volta de quem recebeu o PIX para quem pagou. */
    @Transactional
    @RabbitListener(queues = "pix.refund.approved.queue")
    public void onPixRefundApproved(PixRefundApprovedEvent event) {
        if (!processedEventRepository.tryMarkAsProcessed(event.refundId())) {
            logger.warn("Estorno PIX já processado, ignorando reentrega (refundId={})", event.refundId());
            return;
        }

        Optional<String> failure = transfer(event.fromAccountId(), event.toAccountId(), new Money(event.amount()),
                EntryType.PIX_REFUND_SENT, EntryType.PIX_REFUND_RECEIVED, event.refundId());

        if (failure.isPresent()) {
            logger.warn("Falha ao efetivar estorno PIX (refundId={}): {}", event.refundId(), failure.get());
            outbox.publish("pix.exchange", "pix.refund.failed", new PixRefundFailedEvent(event.refundId(), failure.get()));
            // quem precisa saber é quem pediu o estorno: o pagador original, que receberia o dinheiro de volta
            outbox.publish(ACCOUNT_EXCHANGE, "account.pix.refund.failed", new AccountPixFailedEvent(event.toAccountId(),
                    event.refundId(), event.amount(), failure.get(), Instant.now()));
            return;
        }

        outbox.publish("pix.exchange", "pix.refund.completed", event.refundId());
        publishMovements(event.fromAccountId(), event.toAccountId(), event.refundId(), event.amount(),
                "account.pix.refund.sent", "account.pix.refund.received");
    }

    /** Um evento por conta afetada, na mesma transação do débito/crédito (outbox). */
    private void publishMovements(UUID fromAccountId, UUID toAccountId, UUID referenceId, BigDecimal amount,
                                  String debitRoutingKey, String creditRoutingKey) {
        Instant now = Instant.now();
        outbox.publish(ACCOUNT_EXCHANGE, debitRoutingKey, new AccountPixMovementEvent(fromAccountId, referenceId, amount, now));
        outbox.publish(ACCOUNT_EXCHANGE, creditRoutingKey, new AccountPixMovementEvent(toAccountId, referenceId, amount, now));
    }

    /**
     * Debita uma conta, credita a outra e grava os dois lançamentos — tudo ou nada.
     *
     * Regra de negócio violada (saldo, conta bloqueada…) não melhora com retry: em vez de deixar a
     * exceção mandar a mensagem para a DLQ (e a operação ficar presa no Payment), devolve o motivo
     * para o chamador publicar a falha. Nada foi salvo, e o evento continua marcado como processado:
     * a decisão sobre ele já foi tomada.
     */
    private Optional<String> transfer(UUID fromAccountId, UUID toAccountId, Money amount,
                                      EntryType debitType, EntryType creditType, UUID referenceId) {
        try {
            Account from = accountRepository.findById(fromAccountId)
                    .orElseThrow(() -> new BusinessRuleException("Conta de origem não encontrada"));
            Account to = accountRepository.findById(toAccountId)
                    .orElseThrow(() -> new BusinessRuleException("Conta de destino não encontrada"));

            from.debit(amount);
            to.credit(amount);

            Account savedFrom = accountRepository.save(from);
            Account savedTo = accountRepository.save(to);
            entryRepository.save(AccountEntry.of(savedFrom, debitType, amount, referenceId));
            entryRepository.save(AccountEntry.of(savedTo, creditType, amount, referenceId));
            return Optional.empty();
        } catch (BusinessRuleException e) {
            return Optional.of(e.getMessage());
        }
    }
}
