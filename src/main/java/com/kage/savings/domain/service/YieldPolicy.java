package com.kage.savings.domain.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Rendimento das caixinhas: 100% do CDI, capitalizado por dia útil. A taxa diária vem da série do
 * Banco Central (já diária) ou, na fonte fixa, da taxa anual pela convenção de 252 dias úteis:
 * fator diário = (1 + CDI anual)^(1/252) - 1.
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
}
