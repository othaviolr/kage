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

    /** Próximo nosso número dos boletos de fatura. */
    long nextOurNumber();
}
