package com.kage.notification.domain.service;

import java.util.Optional;
import java.util.UUID;

/** Quem é o cliente por trás de uma conta ou de um cartão: os eventos chegam com o id do recurso. */
public interface RecipientDirectory {

    Optional<UUID> customerOfAccount(UUID accountId);

    Optional<UUID> customerOfCard(UUID cardId);
}
