package com.kage.payment.domain.service;

import java.util.Optional;
import java.util.UUID;

/** Porta para o Account: quem é o titular de uma conta (o nome que o pagador vê no pedido). */
public interface AccountHolderDirectory {

    /**
     * @param document CPF do titular, sem máscara (quem mostra mascara)
     * @param account  número-dígito
     * @param city     cidade do endereço do titular (vai no PIX copia e cola que ele gera para cobrar)
     */
    record Holder(UUID accountId, String name, String document, String branch, String account, boolean active,
                  String city) {}

    Optional<Holder> holderOf(UUID accountId);
}
