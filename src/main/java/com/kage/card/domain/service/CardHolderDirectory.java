package com.kage.card.domain.service;

import java.util.Optional;
import java.util.UUID;

/** Nome do titular do cartão, para documentos (a fatura). Implementado pelo contexto de clientes. */
public interface CardHolderDirectory {

    Optional<String> nameOf(UUID customerId);
}
