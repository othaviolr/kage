package com.kage.payment.application.usecase;

import com.kage.payment.domain.entity.PixRefund;
import com.kage.payment.domain.repository.PixRefundRepository;
import com.kage.shared.domain.exception.DomainException;

import java.util.UUID;

public class RejectPixRefund {

    private final PixRefundRepository pixRefundRepository;

    public RejectPixRefund(PixRefundRepository pixRefundRepository) {
        this.pixRefundRepository = pixRefundRepository;
    }

    public void execute(UUID refundId, String processedBy) {
        PixRefund refund = pixRefundRepository.findById(refundId).orElseThrow(() -> new DomainException("Estorno não encontrado"));

        refund.reject(processedBy);
        pixRefundRepository.save(refund);
    }
}