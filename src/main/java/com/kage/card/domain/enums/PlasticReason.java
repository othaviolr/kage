package com.kage.card.domain.enums;

/** Por que um plástico foi emitido (ou cancelado). */
public enum PlasticReason {
    /** Emissão do cartão. */
    FIRST_ISSUE,
    LOST,
    STOLEN,
    /** Número comprometido (compra não reconhecida, clonagem). */
    FRAUD,
    /** Cartão quebrado, chip ou tarja com defeito: o antigo vale até a 2ª via ser desbloqueada. */
    DAMAGED,
    /** Substituído pela 2ª via desbloqueada. */
    REPLACED,
    /** O cartão (conta de crédito) foi cancelado. */
    CARD_CANCELLED;

    /** Motivos que o cliente informa ao pedir a 2ª via. */
    public boolean isReissueReason() {
        return this == LOST || this == STOLEN || this == FRAUD || this == DAMAGED;
    }

    /** O número antigo pode estar com outra pessoa: ele morre na hora. */
    public boolean compromisesNumber() {
        return this == LOST || this == STOLEN || this == FRAUD;
    }
}
