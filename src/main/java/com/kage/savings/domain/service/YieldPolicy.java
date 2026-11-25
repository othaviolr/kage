package com.kage.savings.domain.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * Rendimento das caixinhas: 100% do CDI, capitalizado em dias úteis (convenção de 252 dias úteis
 * por ano, a mesma do mercado: fator diário = (1 + CDI anual)^(1/252) - 1).
 *
 * Dia útil aqui é segunda a sexta. Feriados nacionais (que no mercado não rendem) ficam de fora
 * desta versão — o calendário da B3 entraria como uma porta.
 */
public final class YieldPolicy {

    /** Precisão do rendimento acumulado ainda não creditado (só centavos inteiros vão para o saldo). */
    public static final int PENDING_SCALE = 10;

    private YieldPolicy() {
    }

    public static BigDecimal dailyRate(BigDecimal annualCdi) {
        double daily = Math.pow(1 + annualCdi.doubleValue(), 1.0 / 252) - 1;
        return new BigDecimal(daily, MathContext.DECIMAL64).setScale(PENDING_SCALE + 4, RoundingMode.HALF_UP);
    }

    public static boolean isBusinessDay(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
    }

    /** Dias úteis em (from, to] — ou seja, depois de {@code from} até {@code to}, inclusive. */
    public static int businessDaysBetween(LocalDate fromExclusive, LocalDate toInclusive) {
        int days = 0;
        for (LocalDate d = fromExclusive.plusDays(1); !d.isAfter(toInclusive); d = d.plusDays(1)) {
            if (isBusinessDay(d)) days++;
        }
        return days;
    }
}
