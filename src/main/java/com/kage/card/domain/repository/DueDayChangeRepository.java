package com.kage.card.domain.repository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/** Histórico das trocas de dia de vencimento (a regra de uma troca a cada 180 dias sai daqui). */
public interface DueDayChangeRepository {

    record Change(UUID cardId, int previousClosingDay, int previousDueDay, int closingDay, int dueDay,
                  LocalDateTime changedAt) {}

    void insert(Change change);

    Optional<LocalDateTime> lastChangeAt(UUID cardId);
}
