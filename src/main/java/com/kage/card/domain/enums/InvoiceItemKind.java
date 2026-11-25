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
    ANTICIPATION
}
