package com.kage.payment.application.usecase;

import com.kage.payment.domain.entity.PixTransaction;
import com.kage.payment.domain.repository.PixTransactionRepository;
import com.kage.shared.domain.exception.DomainException;
import com.kage.shared.domain.exception.NotFoundException;

import java.math.BigDecimal;
import java.util.UUID;

public class GetPixTransaction {

    private final PixTransactionRepository pixTransactionRepository;

    public GetPixTransaction(PixTransactionRepository pixTransactionRepository) {
        this.pixTransactionRepository = pixTransactionRepository;
    }

    public Output execute(UUID transactionId) {
        PixTransaction transaction = pixTransactionRepository.findById(transactionId).orElseThrow(() -> new NotFoundException("Transação PIX não encontrada"));

        return new Output(
                transaction.getTransactionId(),
                transaction.getSourceAccountId(),
                transaction.getTargetPixKey(),
                transaction.getTargetAccountId(),
                transaction.getAmount().amount(),
                transaction.getDescription(),
                transaction.getTransactionType().name(),
                transaction.getStatus().name(),
                transaction.getFailureReason(),
                transaction.getE2eId(),
                transaction.getCreatedAt().toString(),
                transaction.getProcessedAt() != null ? transaction.getProcessedAt().toString() : null,
                transaction.getCompletedAt() != null ? transaction.getCompletedAt().toString() : null);
    }

    public record Output(
            UUID transactionId,
            UUID sourceAccountId,
            String targetPixKey,
            UUID targetAccountId,
            BigDecimal amount,
            String description,
            String transactionType,
            String status,
            String failureReason,
            String e2eId,
            String createdAt,
            String processedAt,
            String completedAt
    ) {}
}