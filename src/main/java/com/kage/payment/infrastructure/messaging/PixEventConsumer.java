package com.kage.payment.infrastructure.messaging;

import com.kage.payment.domain.entity.PixTransaction;
import com.kage.payment.domain.repository.PixTransactionRepository;
import com.kage.payment.infrastructure.persistence.mapper.PixTransactionMapper;
import com.kage.shared.domain.exception.DomainException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

import java.util.UUID;

public class PixEventConsumer {

    private final PixTransactionRepository pixTransactionRepository;

    public PixEventConsumer(PixTransactionRepository pixTransactionRepository) {
        this.pixTransactionRepository = pixTransactionRepository;
    }

    @RabbitListener(queues = "pix.debit.confirmed.queue")
    public void onPixDebitConfirmed(UUID transactionId) {
        PixTransaction transaction = pixTransactionRepository.findById(transactionId).orElseThrow(() -> new DomainException("Transação PIX não encontrada"));

        transaction.complete();
        pixTransactionRepository.save(transaction);
    }
}