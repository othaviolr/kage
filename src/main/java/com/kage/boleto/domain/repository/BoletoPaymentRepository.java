package com.kage.boleto.domain.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Pagamentos de boleto feitos por clientes do Kage (de boletos daqui ou de outros bancos). */
public interface BoletoPaymentRepository {

    enum Kind { INTERNAL, EXTERNAL }

    record Payment(UUID id, UUID payerAccountId, String barcode, String bankCode, Kind kind, UUID boletoId,
                   String beneficiaryName, LocalDate dueDate, BigDecimal faceAmount, BigDecimal fine,
                   BigDecimal interest, BigDecimal paidAmount, LocalDateTime paidAt) {}

    /**
     * Um código de barras só é pago uma vez (índice único): pagar duas vezes a mesma conta é o erro
     * mais comum com boleto. @throws org.springframework.dao.DuplicateKeyException se já foi pago
     */
    void insert(Payment payment);

    boolean existsByBarcode(String barcode);

    List<Payment> findByPayerAccount(UUID accountId, int limit);

    java.util.Optional<Payment> findById(UUID id);
}
