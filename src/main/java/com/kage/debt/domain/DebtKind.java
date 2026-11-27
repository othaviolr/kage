package com.kage.debt.domain;

public enum DebtKind {
    /** Fatura fechada com saldo (esperando o vencimento, ou já vencida). */
    CARD_INVOICE,
    /** Fatura aberta do ciclo atual: as compras do mês e as parcelas que caem nela. */
    CARD_CURRENT_INVOICE,
    /** Parcelas futuras de uma compra parcelada sem juros. */
    CARD_PURCHASE_INSTALLMENTS,
    /** Parcelas futuras do parcelamento de uma fatura (com juros). */
    CARD_INVOICE_FINANCING,
    /** Empréstimo pessoal. */
    LOAN,
    /** Acordo de renegociação de um empréstimo em atraso. */
    LOAN_AGREEMENT
}
