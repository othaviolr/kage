package com.kage.payment.application.usecase;

import com.kage.payment.domain.entity.PixRefund;
import com.kage.payment.domain.entity.PixTransaction;
import com.kage.payment.domain.repository.PixRefundRepository;
import com.kage.payment.domain.repository.PixTransactionRepository;
import com.kage.shared.domain.exception.NotFoundException;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Aprova o estorno e pede ao Account a devolução do dinheiro. O estorno fica APPROVED até o Account
 * responder: COMPLETED se a devolução foi feita, FAILED se foi recusada (ex.: quem recebeu já gastou).
 */
public class ApprovePixRefund {

    private final PixRefundRepository pixRefundRepository;
    private final PixTransactionRepository pixTransactionRepository;
    private final PixEventPublisher pixEventPublisher;

    public ApprovePixRefund(PixRefundRepository pixRefundRepository, PixTransactionRepository pixTransactionRepository,
                            PixEventPublisher pixEventPublisher) {
        this.pixRefundRepository = pixRefundRepository;
        this.pixTransactionRepository = pixTransactionRepository;
        this.pixEventPublisher = pixEventPublisher;
    }

    @Transactional
    public void execute(UUID refundId, String processedBy) {
        PixRefund refund = pixRefundRepository.findById(refundId).orElseThrow(() -> new NotFoundException("Estorno não encontrado"));
        PixTransaction original = pixTransactionRepository.findById(refund.getOriginalTransactionId())
                .orElseThrow(() -> new NotFoundException("Transação PIX original não encontrada"));

        refund.approve(processedBy);
        pixRefundRepository.save(refund);

        pixEventPublisher.publishRefundApproved(refund, original);
    }
}
