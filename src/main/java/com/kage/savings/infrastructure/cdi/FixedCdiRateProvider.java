package com.kage.savings.infrastructure.cdi;

import com.kage.savings.domain.service.BusinessCalendar;
import com.kage.savings.domain.service.CdiRateProvider;
import com.kage.savings.domain.service.YieldPolicy;
import com.kage.savings.domain.valueobject.CdiSchedule;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * CDI anual fixo (configuração) nos dias úteis do calendário nacional. Para desenvolvimento, testes
 * e para simular datas futuras pelo backoffice — a série real só tem o passado.
 */
public class FixedCdiRateProvider implements CdiRateProvider {

    private final BigDecimal dailyRate;
    private final String source;

    public FixedCdiRateProvider(BigDecimal annualCdi) {
        this.dailyRate = YieldPolicy.dailyRate(annualCdi);
        this.source = "FIXED " + annualCdi.movePointRight(2).stripTrailingZeros().toPlainString() + "% a.a.";
    }

    @Override
    public CdiSchedule schedule(LocalDate fromExclusive, LocalDate toInclusive) {
        Map<LocalDate, BigDecimal> rates = new HashMap<>();
        for (LocalDate d = fromExclusive.plusDays(1); !d.isAfter(toInclusive); d = d.plusDays(1)) {
            if (BusinessCalendar.isBusinessDay(d)) rates.put(d, dailyRate);
        }
        return new CdiSchedule(rates, toInclusive, source);
    }
}
