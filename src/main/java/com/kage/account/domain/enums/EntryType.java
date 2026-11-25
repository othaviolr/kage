package com.kage.account.domain.enums;

public enum EntryType {
    DEPOSIT(true),
    WITHDRAWAL(false),
    PIX_RECEIVED(true),
    PIX_SENT(false),
    PIX_REFUND_RECEIVED(true),
    PIX_REFUND_SENT(false),
    INVOICE_PAYMENT(false),
    /** Guardou na caixinha (sai da conta). */
    SAVINGS_DEPOSIT(false),
    /** Resgatou da caixinha (volta para a conta). */
    SAVINGS_WITHDRAWAL(true),
    /** Pagou um boleto. */
    BOLETO_PAYMENT(false),
    /** Recebeu o pagamento de um boleto que emitiu. */
    BOLETO_RECEIVED(true);

    private final boolean credit;

    EntryType(boolean credit) {
        this.credit = credit;
    }

    public boolean isCredit() {
        return credit;
    }
}
