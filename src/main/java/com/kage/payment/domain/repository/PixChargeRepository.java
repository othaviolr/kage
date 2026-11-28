package com.kage.payment.domain.repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Cobranças PIX que os clientes geram para receber, e quem pagou cada uma. */
public interface PixChargeRepository {

    /**
     * @param amount nulo = quem paga escolhe o valor
     * @param status OPEN ou CANCELLED (pago é calculado a partir dos pagamentos)
     */
    record Charge(UUID id, UUID accountId, String pixKey, String txid, BigDecimal amount, String message,
                  boolean singleUse, String code, String status, LocalDateTime createdAt, LocalDateTime cancelledAt) {}

    record Payment(UUID transactionId, UUID chargeId, UUID payerAccountId, BigDecimal amount, LocalDateTime paidAt) {}

    void insert(Charge charge);

    Optional<Charge> findById(UUID id);

    /** Trava a cobrança até o fim da transação: dois pagamentos simultâneos não pagam a de uso único duas vezes. */
    Optional<Charge> findByIdForUpdate(UUID id);

    Optional<Charge> findByTxid(String txid);

    /** Mais recentes primeiro. */
    List<Charge> findByAccountId(UUID accountId, int limit);

    void cancel(UUID id, LocalDateTime at);

    void insertPayment(Payment payment);

    /** Mais antigos primeiro. */
    List<Payment> paymentsOf(UUID chargeId);
}
