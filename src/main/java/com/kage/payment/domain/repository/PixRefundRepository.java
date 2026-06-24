package com.kage.payment.domain.repository;

import com.kage.payment.domain.entity.PixRefund;

import java.util.Optional;
import java.util.UUID;

public interface PixRefundRepository {
    PixRefund save(PixRefund refund);
    Optional<PixRefund> findById(UUID refundId);
    Optional<PixRefund> findByOriginalTransactionId(UUID originalTransactionId);
}