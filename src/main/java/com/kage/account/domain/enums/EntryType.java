package com.kage.account.domain.enums;

public enum EntryType {
    DEPOSIT(true, "Depósito"),
    WITHDRAWAL(false, "Saque"),
    PIX_RECEIVED(true, "PIX recebido"),
    PIX_SENT(false, "PIX enviado"),
    PIX_REFUND_RECEIVED(true, "Estorno de PIX recebido"),
    PIX_REFUND_SENT(false, "Estorno de PIX devolvido"),
    INVOICE_PAYMENT(false, "Pagamento da fatura do cartão"),
    /** Guardou na caixinha (sai da conta). */
    SAVINGS_DEPOSIT(false, "Guardado na caixinha"),
    /** Resgatou da caixinha (volta para a conta). */
    SAVINGS_WITHDRAWAL(true, "Resgate da caixinha"),
    /** Pagou um boleto. */
    BOLETO_PAYMENT(false, "Pagamento de boleto"),
    /** Recebeu o pagamento de um boleto que emitiu. */
    BOLETO_RECEIVED(true, "Boleto recebido");

    private final boolean credit;
    private final String label;

    EntryType(boolean credit, String label) {
        this.credit = credit;
        this.label = label;
    }

    public boolean isCredit() {
        return credit;
    }

    /** Como aparece no extrato do app e no arquivo exportado. */
    public String label() {
        return label;
    }
}
