package com.kage.card.infrastructure.messaging;

import com.kage.shared.domain.DomainTime;
import com.kage.card.application.usecase.CardEventPublisher;
import com.kage.card.domain.entity.Dispute;
import com.kage.card.domain.entity.Invoice;
import com.kage.shared.domain.valueobject.Money;
import com.kage.shared.infrastructure.messaging.OutboxEventPublisher;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

public class OutboxCardEventPublisher implements CardEventPublisher {

    private final OutboxEventPublisher outbox;

    public OutboxCardEventPublisher(OutboxEventPublisher outbox) {
        this.outbox = outbox;
    }

    @Override
    public void publishInvoiceClosed(Invoice invoice) {
        outbox.publish(CardEvents.EXCHANGE, CardEvents.INVOICE_CLOSED, new CardEvents.InvoiceClosedEvent(
                invoice.getCardId(), invoice.getId(), invoice.getReferenceMonth().toString(), invoice.total().amount(),
                invoice.getDueDate(), DomainTime.instant()));
    }

    @Override
    public void publishInvoicePaid(Invoice invoice) {
        outbox.publish(CardEvents.EXCHANGE, CardEvents.INVOICE_PAID, new CardEvents.InvoicePaidEvent(
                invoice.getCardId(), invoice.getId(), invoice.getReferenceMonth().toString(), invoice.total().amount(),
                DomainTime.instant()));
    }

    @Override
    public void publishInvoicePartiallyPaid(Invoice invoice, Money amountPaid) {
        outbox.publish(CardEvents.EXCHANGE, CardEvents.INVOICE_PARTIALLY_PAID, new CardEvents.InvoicePartiallyPaidEvent(
                invoice.getCardId(), invoice.getId(), invoice.getReferenceMonth().toString(), amountPaid.amount(),
                invoice.remaining().amount(), invoice.minimumPayment().amount(), invoice.getPaidAmount().amount(),
                DomainTime.instant()));
    }

    @Override
    public void publishInvoiceRolledOver(Invoice from, Invoice to, Money carried, Money charges, boolean late) {
        outbox.publish(CardEvents.EXCHANGE, CardEvents.INVOICE_ROLLED_OVER, new CardEvents.InvoiceRolledOverEvent(
                from.getCardId(), from.getReferenceMonth().toString(), to.getReferenceMonth().toString(),
                carried.amount(), charges.amount(), late, DomainTime.instant()));
    }

    @Override
    public void publishInvoiceFinanced(Invoice invoice, int installments, Money installmentAmount, Money total, boolean automatic) {
        outbox.publish(CardEvents.EXCHANGE, CardEvents.INVOICE_FINANCED, new CardEvents.InvoiceFinancedEvent(
                invoice.getCardId(), invoice.getId(), invoice.getReferenceMonth().toString(), installments,
                installmentAmount.amount(), total.amount(), automatic, DomainTime.instant()));
    }

    @Override
    public void publishDisputeOpened(Dispute d) {
        outbox.publish(CardEvents.EXCHANGE, CardEvents.DISPUTE_OPENED, new CardEvents.DisputeOpenedEvent(
                d.cardId(), d.id(), d.description(), d.amount().amount(), d.creditedAmount().amount(), d.creditMonth(),
                d.reason().name(), d.cardBlocked(), d.virtualCardDeleted(), DomainTime.instant()));
    }

    @Override
    public void publishDisputeResolved(Dispute d) {
        outbox.publish(CardEvents.EXCHANGE, CardEvents.DISPUTE_RESOLVED, new CardEvents.DisputeResolvedEvent(
                d.cardId(), d.id(), d.description(), d.amount().amount(), d.status().name(), d.resolutionNote(),
                d.reversalMonth(), DomainTime.instant()));
    }

    @Override
    @Transactional
    public void publishAutoDebitFailed(UUID cardId, String referenceMonth, String reason) {
        outbox.publish(CardEvents.EXCHANGE, CardEvents.AUTO_DEBIT_FAILED,
                new CardEvents.InvoiceAutoDebitFailedEvent(cardId, referenceMonth, reason, DomainTime.instant()));
    }
}
