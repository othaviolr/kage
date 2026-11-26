package com.kage.card.domain.enums;

/** Gravado; "expirado" sai do relógio (VirtualCard.isExpired). */
public enum VirtualCardStatus {
    ACTIVE,
    BLOCKED,
    /** Apagado pelo cliente (ou pelo banco: fraude, cartão físico cancelado). Definitivo. */
    DELETED
}
