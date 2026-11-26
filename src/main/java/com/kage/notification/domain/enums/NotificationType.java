package com.kage.notification.domain.enums;

import static com.kage.notification.domain.enums.NotificationCategory.ACCOUNT;
import static com.kage.notification.domain.enums.NotificationCategory.BOLETO;
import static com.kage.notification.domain.enums.NotificationCategory.CARD_PURCHASES;
import static com.kage.notification.domain.enums.NotificationCategory.CARD_SERVICES;
import static com.kage.notification.domain.enums.NotificationCategory.INVOICE;
import static com.kage.notification.domain.enums.NotificationCategory.LOANS;
import static com.kage.notification.domain.enums.NotificationCategory.PIX;
import static com.kage.notification.domain.enums.NotificationCategory.SAVINGS;
import static com.kage.notification.domain.enums.NotificationCategory.SECURITY;

/**
 * Cada aviso pertence a uma categoria (o que o cliente liga/desliga). Os {@code critical} vão por push
 * mesmo com a categoria desligada: algo deu errado com o dinheiro dele (PIX que não saiu, estorno que
 * falhou, compra recusada — pode ser tentativa de fraude) ou ele precisa agir (autorizar um PIX
 * Automático).
 */
public enum NotificationType {
    DEPOSIT_RECEIVED(ACCOUNT, false),
    WITHDRAWAL_MADE(ACCOUNT, false),
    PIX_SENT(PIX, false),
    PIX_RECEIVED(PIX, false),
    PIX_FAILED(PIX, true),
    PIX_SCHEDULED_FAILED(PIX, true),
    PIX_AUTOMATIC_REQUESTED(PIX, true),
    PIX_AUTOMATIC_CHARGE_SCHEDULED(PIX, false),
    PIX_REFUND_SENT(PIX, false),
    PIX_REFUND_RECEIVED(PIX, false),
    PIX_REFUND_FAILED(PIX, true),
    INVOICE_CLOSED(INVOICE, false),
    INVOICE_PAID(INVOICE, false),
    INVOICE_AUTO_DEBIT_FAILED(INVOICE, true),
    INVOICE_PARTIALLY_PAID(INVOICE, false),
    INVOICE_ROLLED_OVER(INVOICE, false),
    INVOICE_FINANCED(INVOICE, false),
    CARD_DISPUTE_OPENED(CARD_SERVICES, false),
    CARD_DISPUTE_RESOLVED(CARD_SERVICES, false),
    CARD_REISSUED(CARD_SERVICES, false),
    CARD_LIMIT_OFFERED(CARD_SERVICES, false),
    CARD_PURCHASE_APPROVED(CARD_PURCHASES, false),
    CARD_PURCHASE_DECLINED(CARD_PURCHASES, true),
    CARD_CASHBACK_EARNED(CARD_PURCHASES, false),
    PASSWORD_CHANGED(SECURITY, true),
    NEW_DEVICE_LOGIN(SECURITY, true),
    LIMIT_INCREASE_REQUESTED(SECURITY, true),
    SAVINGS_GOAL_REACHED(SAVINGS, false),
    BOLETO_RECEIVED(BOLETO, false),
    BOLETO_PAYMENT_MADE(BOLETO, false),
    LOAN_CONTRACTED(LOANS, false),
    LOAN_INSTALLMENT_PAID(LOANS, false),
    /** O débito da parcela não passou: o atraso já conta multa e mora. */
    LOAN_INSTALLMENT_FAILED(LOANS, true),
    LOAN_PAID_OFF(LOANS, false),
    LOAN_WITHDRAWN(LOANS, false),
    LOAN_RENEGOTIATED(LOANS, false),
    LOAN_INSTALLMENT_DUE_SOON(LOANS, false),
    /** Resumo ao fim do horário silencioso: gerado pelo job, sem categoria (não liga/desliga). */
    QUIET_HOURS_SUMMARY(null, false);

    private final NotificationCategory category;
    private final boolean critical;

    NotificationType(NotificationCategory category, boolean critical) {
        this.category = category;
        this.critical = critical;
    }

    public NotificationCategory category() {
        return category;
    }

    /** Vai por push mesmo com a categoria desligada. */
    public boolean isCritical() {
        return critical;
    }

    /** Push sai se o aviso é crítico, se a categoria é obrigatória ou se o cliente não a desligou. */
    public boolean shouldPush(java.util.Set<NotificationCategory> disabled) {
        return critical || category == null || category.isMandatory() || !disabled.contains(category);
    }
}
