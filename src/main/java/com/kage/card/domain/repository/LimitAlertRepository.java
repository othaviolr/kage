package com.kage.card.domain.repository;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.UUID;

/** Faixas de uso do limite já avisadas, uma por cartão, fatura e faixa. */
public interface LimitAlertRepository {

    /** @return false se essa faixa já tinha sido avisada nessa fatura (chave primária) */
    boolean markAlerted(UUID cardId, YearMonth referenceMonth, int threshold, LocalDateTime alertedAt);
}
