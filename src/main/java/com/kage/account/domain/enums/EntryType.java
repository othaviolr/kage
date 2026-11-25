package com.kage.account.domain.enums;

public enum EntryType {
    DEPOSIT(true),
    WITHDRAWAL(false),
    PIX_RECEIVED(true),
    PIX_SENT(false),
    PIX_REFUND_RECEIVED(true),
    PIX_REFUND_SENT(false),
    INVOICE_PAYMENT(false);

    private final boolean credit;

    EntryType(boolean credit) {
        this.credit = credit;
    }

    public boolean isCredit() {
        return credit;
    }
}
