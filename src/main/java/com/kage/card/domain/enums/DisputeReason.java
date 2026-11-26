package com.kage.card.domain.enums;

/** Por que o cliente contesta a compra (os motivos de chargeback das bandeiras, simplificados). */
public enum DisputeReason {
    /** Não reconhece a compra: suspeita de fraude. O cartão é bloqueado na hora. */
    NOT_RECOGNIZED,
    /** A mesma compra foi cobrada mais de uma vez. */
    DUPLICATE_CHARGE,
    /** Pagou e não recebeu o produto ou o serviço. */
    NOT_RECEIVED,
    /** Cancelou a compra (ou devolveu o produto) e o estorno não veio. */
    CANCELLED_PURCHASE;

    public boolean isFraud() {
        return this == NOT_RECOGNIZED;
    }
}
