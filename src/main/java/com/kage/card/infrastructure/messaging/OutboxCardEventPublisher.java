package com.kage.card.infrastructure.messaging;

import com.kage.shared.domain.DomainTime;
import com.kage.card.application.usecase.CardEventPublisher;
import com.kage.card.domain.entity.CardPlastic;
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
                d.reversalMonth(), d.interestRefunded().amount(), DomainTime.instant()));
    }

    @Override
    public void publishCardReissued(CardPlastic p, boolean oldNumberCancelled) {
        outbox.publish(CardEvents.EXCHANGE, CardEvents.CARD_REISSUED, new CardEvents.CardReissuedEvent(
                p.cardId(), p.id(), p.lastDigits(), p.issueReason().name(), oldNumberCancelled, DomainTime.instant()));
    }

    @Override
    public void publishPurchaseApproved(UUID cardId, UUID purchaseId, String description, Money amount, int installments,
                                        Money installmentAmount, Money availableLimit, String virtualCardNickname) {
        outbox.publish(CardEvents.EXCHANGE, CardEvents.PURCHASE_APPROVED, new CardEvents.PurchaseApprovedEvent(
                cardId, purchaseId, description, amount.amount(), installments, installmentAmount.amount(),
                availableLimit.amount(), virtualCardNickname, DomainTime.instant()));
    }

    @Override
    public void publishCashbackEarned(UUID cardId, String referenceMonth, Money amount, String destination) {
        outbox.publish(CardEvents.EXCHANGE, CardEvents.CASHBACK_EARNED, new CardEvents.CashbackEarnedEvent(
                cardId, referenceMonth, amount.amount(), destination, DomainTime.instant()));
    }

    @Override
    public void publishLimitUsageAlert(UUID cardId, String referenceMonth, int threshold, int usagePercent,
                                       Money usedLimit, Money creditLimit, Money approvedLimit, Money availableLimit) {
        outbox.publish(CardEvents.EXCHANGE, CardEvents.LIMIT_USAGE_ALERT, new CardEvents.LimitUsageAlertEvent(
                cardId, referenceMonth, threshold, usagePercent, usedLimit.amount(), creditLimit.amount(),
                approvedLimit.amount(), availableLimit.amount(), DomainTime.instant()));
    }

    @Override
    public void publishDueDayChanged(UUID cardId, int dueDay, int closingDay, String transitionMonth,
                                     java.time.LocalDate transitionDueDate, Long transitionDays) {
        outbox.publish(CardEvents.EXCHANGE, CardEvents.DUE_DAY_CHANGED, new CardEvents.DueDayChangedEvent(
                cardId, dueDay, closingDay, transitionMonth, transitionDueDate, transitionDays, DomainTime.instant()));
    }

    @Override
    public void publishLimitOffered(UUID cardId, UUID offerId, Money offeredLimit, java.time.LocalDateTime expiresAt) {
        outbox.publish(CardEvents.EXCHANGE, CardEvents.LIMIT_OFFERED, new CardEvents.LimitOfferedEvent(
                cardId, offerId, offeredLimit.amount(), expiresAt, DomainTime.instant()));
    }

    @Override
    @Transactional
    public void publishPurchaseDeclined(UUID cardId, String description, Money amount, String reason,
                                        String virtualCardNickname) {
        publishPurchaseDeclined(cardId, description, amount, reason, virtualCardNickname, null);
    }

    @Override
    @Transactional
    public void publishPurchaseDeclined(UUID cardId, String description, Money amount, String reason,
                                        String virtualCardNickname, String currency) {
        outbox.publish(CardEvents.EXCHANGE, CardEvents.PURCHASE_DECLINED, new CardEvents.PurchaseDeclinedEvent(
                cardId, description, amount.amount(), reason, virtualCardNickname, currency, DomainTime.instant()));
    }

    @Override
    @Transactional
    public void publishAutoDebitFailed(UUID cardId, String referenceMonth, String reason) {
        outbox.publish(CardEvents.EXCHANGE, CardEvents.AUTO_DEBIT_FAILED,
                new CardEvents.InvoiceAutoDebitFailedEvent(cardId, referenceMonth, reason, DomainTime.instant()));
    }
}
