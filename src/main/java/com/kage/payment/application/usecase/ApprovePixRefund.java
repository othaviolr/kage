package com.kage.payment.application.usecase;

import com.kage.payment.domain.entity.PixRefund;
import com.kage.payment.domain.repository.PixRefundRepository;
import com.kage.shared.domain.exception.DomainException;
import com.kage.shared.domain.exception.NotFoundException;

import java.util.UUID;

public class ApprovePixRefund {

    private final PixRefundRepository pixRefundRepository;

    public ApprovePixRefund(PixRefundRepository pixRefundRepository) {
        this.pixRefundRepository = pixRefundRepository;
    }

    public void execute(UUID refundId, String processedBy) {
        PixRefund refund = pixRefundRepository.findById(refundId).orElseThrow(() -> new NotFoundException("Estorno não encontrado"));

        refund.approve(processedBy);
        refund.complete();
        pixRefundRepository.save(refund);
    }
}