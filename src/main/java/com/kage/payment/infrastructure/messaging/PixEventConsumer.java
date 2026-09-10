package com.kage.payment.infrastructure.messaging;

import com.kage.payment.domain.entity.PixTransaction;
import com.kage.payment.domain.repository.PixTransactionRepository;
import com.kage.payment.infrastructure.persistence.idempotency.ProcessedEventRepository;
import com.kage.shared.domain.exception.DomainException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

public class PixEventConsumer {

    private static final Logger logger = LoggerFactory.getLogger(PixEventConsumer.class);

    private final PixTransactionRepository pixTransactionRepository;
    private final ProcessedEventRepository processedEventRepository;

    public PixEventConsumer(PixTransactionRepository pixTransactionRepository,
                            ProcessedEventRepository processedEventRepository) {
        this.pixTransactionRepository = pixTransactionRepository;
        this.processedEventRepository = processedEventRepository;
    }

    @RabbitListener(queues = "pix.debit.confirmed.queue")
    @Transactional
    public void onPixDebitConfirmed(UUID transactionId) {
        if (!processedEventRepository.tryMarkAsProcessed(transactionId)) {
            logger.warn("Evento de confirmação PIX {} já processado, ignorando reentrega", transactionId);
            return;
        }

        PixTransaction transaction = pixTransactionRepository.findById(transactionId)
                .orElseThrow(() -> new DomainException("Transação PIX não encontrada"));

        transaction.complete();
        pixTransactionRepository.save(transaction);
    }
}