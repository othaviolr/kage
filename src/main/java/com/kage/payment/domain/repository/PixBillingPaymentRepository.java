package com.kage.payment.domain.repository;

import com.kage.shared.domain.valueobject.Money;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/** Cobranças do Kage (PIX copia e cola da fatura) pagas pelo PIX da conta. */
public interface PixBillingPaymentRepository {

    record Payment(UUID id, UUID accountId, UUID chargeId, String kind, String code, String beneficiary,
                   String description, BigDecimal amount, LocalDateTime paidAt) {}

    /** @throws org.springframework.dao.DuplicateKeyException se a cobrança já foi paga */
    void insert(Payment payment);

    Optional<Payment> findById(UUID id);

    /** Quanto a conta já pagou assim desde {@code since}: conta no limite de PIX. */
    Money sumPaidSince(UUID accountId, LocalDateTime since);
}
