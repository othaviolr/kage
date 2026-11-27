package com.kage.card.domain.service;

import com.kage.shared.domain.exception.BusinessRuleException;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Cotação de venda da moeda (PTAX do Banco Central, ou uma taxa fixa de configuração). */
public interface ExchangeRateProvider {

    /**
     * @param rate  reais por 1 unidade da moeda
     * @param date  dia da cotação (o útil anterior, se a compra caiu em fim de semana ou feriado)
     */
    record Rate(String currency, BigDecimal rate, LocalDate date, String source) {}

    /** @throws ExchangeRateUnavailableException fonte fora do ar ou moeda sem cotação */
    Rate sellingRate(String currency, LocalDate date);

    /** Sem cotação, a compra é recusada: melhor recusar do que cobrar um valor que não dá para explicar. */
    class ExchangeRateUnavailableException extends BusinessRuleException {
        public ExchangeRateUnavailableException(String message) {
            super(message);
        }
    }
}
