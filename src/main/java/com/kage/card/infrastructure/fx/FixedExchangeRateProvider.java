package com.kage.card.infrastructure.fx;

import com.kage.card.domain.service.ExchangeRateProvider;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Cotação fixa de configuração: determinística (testes, coleção do Postman com datas futuras). */
public class FixedExchangeRateProvider implements ExchangeRateProvider {

    static final String SOURCE = "fixa (configuração)";

    private final Map<String, BigDecimal> rates;

    /** @param spec "USD=5.50,EUR=6.00" */
    public FixedExchangeRateProvider(String spec) {
        Map<String, BigDecimal> parsed = new HashMap<>();
        for (String pair : spec.split(",")) {
            String[] kv = pair.trim().split("=");
            if (kv.length != 2) throw new IllegalStateException("kage.fx.fixed-rates inválido: " + spec);
            parsed.put(kv[0].trim().toUpperCase(Locale.ROOT), new BigDecimal(kv[1].trim()));
        }
        this.rates = Map.copyOf(parsed);
    }

    @Override
    public Rate sellingRate(String currency, LocalDate date) {
        BigDecimal rate = rates.get(currency);
        if (rate == null) throw new ExchangeRateUnavailableException("Sem cotação para " + currency);
        return new Rate(currency, rate, date, SOURCE);
    }
}
