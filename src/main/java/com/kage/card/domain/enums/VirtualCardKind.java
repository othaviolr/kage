package com.kage.card.domain.enums;

public enum VirtualCardKind {
    /** Para assinaturas e lojas de confiança: vale até a validade (5 anos). */
    PERMANENT,
    /** Para uma compra pontual: expira 24h depois de criado. */
    TEMPORARY
}
