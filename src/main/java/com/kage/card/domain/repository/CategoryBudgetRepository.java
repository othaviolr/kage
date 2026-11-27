package com.kage.card.domain.repository;

import com.kage.card.domain.enums.PurchaseCategory;
import com.kage.shared.domain.valueobject.Money;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Orçamento mensal por categoria de compra e as faixas (80/100%) já avisadas em cada fatura. */
public interface CategoryBudgetRepository {

    record Budget(PurchaseCategory category, Money amount) {}

    List<Budget> findByCardId(UUID cardId);

    Optional<Budget> find(UUID cardId, PurchaseCategory category);

    /** Cria ou troca o valor; zera as faixas já avisadas da categoria (o orçamento novo avisa de novo). */
    void save(UUID cardId, PurchaseCategory category, Money amount, LocalDateTime at);

    /** @return false se não havia orçamento */
    boolean delete(UUID cardId, PurchaseCategory category);

    /** @return false se essa faixa já tinha sido avisada nessa categoria e fatura (chave primária) */
    boolean markAlerted(UUID cardId, PurchaseCategory category, YearMonth referenceMonth, int threshold,
                        LocalDateTime alertedAt);
}
