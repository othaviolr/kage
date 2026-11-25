package com.kage.savings.domain.valueobject;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

/**
 * Taxas diárias do CDI conhecidas até {@code confirmedUntil}. Até essa data, dia sem taxa é dia sem
 * rendimento (fim de semana ou feriado); depois dela, ainda não se sabe — não rende até saber.
 *
 * @param dailyRates fração ao dia (0.00050788 = 0,050788% a.d.)
 */
public record CdiSchedule(Map<LocalDate, BigDecimal> dailyRates, LocalDate confirmedUntil, String source) {

    public CdiSchedule {
        dailyRates = Map.copyOf(dailyRates);
    }

    public Optional<BigDecimal> rateOn(LocalDate date) {
        if (date.isAfter(confirmedUntil)) return Optional.empty();
        return Optional.ofNullable(dailyRates.get(date));
    }
}
