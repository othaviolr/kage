package com.kage.card.application.usecase;

import com.kage.card.domain.entity.Invoice;

import java.util.UUID;

/** Porta de saída dos eventos da fatura. A implementação grava no outbox. */
public interface CardEventPublisher {

    /** Chamar dentro da transação do fechamento. */
    void publishInvoiceClosed(Invoice invoice);

    /** Chamar dentro da transação do pagamento. */
    void publishInvoicePaid(Invoice invoice);

    /**
     * O débito automático no vencimento não passou. Abre transação própria se não houver uma: a do
     * pagamento já sofreu rollback quando a falha aparece.
     */
    void publishAutoDebitFailed(UUID cardId, String referenceMonth, String reason);
}
