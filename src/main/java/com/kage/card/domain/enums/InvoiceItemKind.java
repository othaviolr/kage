package com.kage.card.domain.enums;

/** O que é cada linha da fatura: compra, ou um encargo da virada do rotativo. */
public enum InvoiceItemKind {
    PURCHASE,
    /** Saldo não pago da fatura anterior, trazido para esta. */
    PREVIOUS_BALANCE,
    /** Juros do crédito rotativo sobre o saldo trazido. */
    REVOLVING_INTEREST,
    /** Multa por atraso (pagou menos que o mínimo). */
    LATE_FINE,
    /** Juros de mora pelos dias de atraso (pagou menos que o mínimo). */
    LATE_INTEREST,
    /** Parcela do parcelamento de uma fatura anterior (com juros). */
    INVOICE_INSTALLMENT,
    /** Parcelas futuras trazidas para esta fatura (antecipação), já com o desconto dos juros. */
    ANTICIPATION,
    /** Crédito de uma compra contestada que já tinha sido cobrada em fatura fechada (abate o total). */
    DISPUTE_CREDIT,
    /** Contestação negada: a compra volta a ser cobrada, à vista. */
    DISPUTE_REVERSAL,
    /** Crédito que sobrou da fatura anterior (os créditos dela passaram do que ela cobrava). */
    CREDIT_BALANCE,
    /** Contestação procedente: devolução dos juros e encargos que a compra gerou. */
    DISPUTE_INTEREST_REFUND,
    /** IOF do rotativo ou do parcelamento da fatura (imposto: fora do teto da Lei 14.690). */
    IOF;

    /** Linha que abate o total da fatura em vez de somar. */
    public boolean isCredit() {
        return this == DISPUTE_CREDIT || this == CREDIT_BALANCE || this == DISPUTE_INTEREST_REFUND;
    }
}
