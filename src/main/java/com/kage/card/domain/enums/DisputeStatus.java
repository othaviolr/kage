package com.kage.card.domain.enums;

public enum DisputeStatus {
    /** Em análise: a cobrança da compra está suspensa. */
    OPEN,
    /** Procedente: o crédito provisório vira definitivo. */
    ACCEPTED,
    /** Improcedente: a compra volta a ser cobrada. */
    REJECTED
}
