package com.kage.payment.application.usecase;

import com.kage.payment.domain.entity.PixRefund;
import com.kage.payment.domain.entity.PixTransaction;
import com.kage.payment.domain.enums.PixTransactionStatus;
import com.kage.payment.domain.repository.PixRefundRepository;
import com.kage.payment.domain.repository.PixTransactionRepository;
import com.kage.shared.domain.exception.*;
import com.kage.shared.domain.valueobject.Money;

import java.util.UUID;

public class RequestPixRefund {

    private final PixTransactionRepository pixTransactionRepository;
    private final PixRefundRepository pixRefundRepository;

    public RequestPixRefund(PixTransactionRepository pixTransactionRepository, PixRefundRepository pixRefundRepository) {
        this.pixTransactionRepository = pixTransactionRepository;
        this.pixRefundRepository = pixRefundRepository;
    }

    public Output execute(Input input) {
        PixTransaction transaction = pixTransactionRepository.findById(input.originalTransactionId()).orElseThrow(() -> new NotFoundException("Transação PIX não encontrada"));

        if (transaction.getStatus() != PixTransactionStatus.COMPLETED) {
            throw new BusinessRuleException("Apenas transações concluídas podem ser estornadas");
        }

        pixRefundRepository.findByOriginalTransactionId(input.originalTransactionId()).ifPresent(r -> { throw new ConflictException("Já existe um estorno para esta transação"); });

        Money refundAmount = input.refundAmount() != null ? input.refundAmount() : transaction.getAmount();

        if (refundAmount.isGreaterThan(transaction.getAmount())) {
            throw new BusinessRuleException("Valor do estorno não pode ser maior que o valor original");
        }

        PixRefund refund = PixRefund.create(input.originalTransactionId(), input.reason(), refundAmount);

        PixRefund saved = pixRefundRepository.save(refund);

        return new Output(saved.getRefundId(), saved.getOriginalTransactionId(), saved.getReason(), saved.getRefundAmount().amount(), saved.getRefundStatus().name(), saved.getRequestedAt().toString());
    }

    public record Input(UUID originalTransactionId, String reason, Money refundAmount) {}

    public record Output(UUID refundId, UUID originalTransactionId, String reason, java.math.BigDecimal refundAmount, String refundStatus, String requestedAt) {}
}