package com.kage.payment.domain.service;

import java.util.Optional;
import java.util.UUID;

/** Porta para o Account: quem é o titular de uma conta (o nome que o pagador vê no pedido). */
public interface AccountHolderDirectory {

    record Holder(UUID accountId, String name, boolean active) {}

    Optional<Holder> holderOf(UUID accountId);
}
