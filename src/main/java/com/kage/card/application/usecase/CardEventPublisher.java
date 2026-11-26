package com.kage.card.application.usecase;

import com.kage.card.domain.entity.Dispute;
import com.kage.card.domain.entity.Invoice;
import com.kage.shared.domain.valueobject.Money;

import java.util.UUID;

/** Porta de saída dos eventos da fatura. A implementação grava no outbox. */
public interface CardEventPublisher {

    /** Chamar dentro da transação do fechamento. */
    void publishInvoiceClosed(Invoice invoice);

    /** Fatura quitada. Chamar dentro da transação do pagamento. */
    void publishInvoicePaid(Invoice invoice);

    /** Pagamento parcial: ainda sobra saldo devedor. Chamar dentro da transação do pagamento. */
    void publishInvoicePartiallyPaid(Invoice invoice, Money amountPaid);

    /** O saldo de {@code from} foi para {@code to} com encargos. Chamar dentro da transação do fechamento. */
    void publishInvoiceRolledOver(Invoice from, Invoice to, Money carried, Money charges, boolean late);

    /**
     * Fatura parcelada (pelo cliente ou automaticamente, pela regra do Bacen). Chamar dentro da
     * transação do parcelamento.
     */
    void publishInvoiceFinanced(Invoice invoice, int installments, Money installmentAmount, Money total, boolean automatic);

    /**
     * O débito automático no vencimento não passou. Abre transação própria se não houver uma: a do
     * pagamento já sofreu rollback quando a falha aparece.
     */
    void publishAutoDebitFailed(UUID cardId, String referenceMonth, String reason);

    /** Compra contestada: a cobrança dela está suspensa. Chamar dentro da transação da abertura. */
    void publishDisputeOpened(Dispute dispute);

    /** Contestação procedente ou negada. Chamar dentro da transação da resolução. */
    void publishDisputeResolved(Dispute dispute);
}
