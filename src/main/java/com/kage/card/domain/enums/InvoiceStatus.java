package com.kage.card.domain.enums;

public enum InvoiceStatus {
    OPEN,
    CLOSED,
    PAID,
    /** O saldo não pago foi para a fatura seguinte (rotativo). */
    ROLLED_OVER
}
