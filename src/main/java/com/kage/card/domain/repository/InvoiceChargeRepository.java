package com.kage.card.domain.repository;

import com.kage.card.domain.entity.InvoiceCharge;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InvoiceChargeRepository {

    void insert(InvoiceCharge charge);

    /**
     * Grava status e data do pagamento. @return false se outra transação mudou a cobrança antes (a
     * condição é o status lido): duas liquidações da mesma cobrança não pagam duas vezes
     */
    boolean update(InvoiceCharge charge, InvoiceCharge.Status expectedStatus);

    Optional<InvoiceCharge> findById(UUID id);

    /** Da fatura, da mais nova para a mais antiga. */
    List<InvoiceCharge> findByInvoiceId(UUID invoiceId);

    /** O boleto de fatura com esse código de barras (44 dígitos). */
    Optional<InvoiceCharge> findBoletoByBarcode(String barcode);

    /** O PIX de fatura com exatamente esse copia e cola. */
    Optional<InvoiceCharge> findPixByCode(String code);

    /** Próximo nosso número dos boletos de fatura. */
    long nextOurNumber();
}
