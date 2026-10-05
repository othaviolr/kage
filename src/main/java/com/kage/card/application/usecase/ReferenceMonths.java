package com.kage.card.application.usecase;

import com.kage.shared.domain.exception.ValidationException;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;

/**
 * Converte o mês de referência recebido na URL ("yyyy-MM") em YearMonth, devolvendo a mesma
 * ValidationException (400) em qualquer use case de fatura que o receba.
 */
final class ReferenceMonths {

    private ReferenceMonths() {
    }

    static YearMonth parse(String referenceMonth) {
        try {
            return YearMonth.parse(referenceMonth);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new ValidationException("Mês de referência inválido. Use o formato yyyy-MM");
        }
    }
}
