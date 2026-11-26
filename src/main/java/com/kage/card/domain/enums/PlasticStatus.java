package com.kage.card.domain.enums;

public enum PlasticStatus {
    /** 2ª via a caminho: só passa a valer quando o cliente desbloqueia com os 4 últimos dígitos. */
    PENDING_ACTIVATION,
    ACTIVE,
    /** Perdido, roubado, fraudado, substituído ou cartão cancelado. Definitivo. */
    CANCELLED
}
