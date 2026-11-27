package com.kage.card.domain.enums;

/** Como a compra foi feita: é o que a bandeira informa na autorização. */
public enum PurchaseChannel {
    /** Cartão físico inserido na maquininha (chip e senha). */
    CHIP,
    /** Cartão físico (ou celular) aproximado da maquininha. */
    CONTACTLESS,
    /** Compra sem o cartão presente: site, app, assinatura. */
    ONLINE
}
