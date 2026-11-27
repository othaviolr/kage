package com.kage.card.infrastructure.controller;

import com.kage.card.domain.service.ExchangeRateProvider;
import com.kage.card.domain.service.InternationalPurchasePolicy;
import com.kage.shared.domain.DomainTime;
import com.kage.shared.domain.exception.ValidationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * "Quanto vai custar em reais?": a cotação de hoje para uma compra internacional no cartão — PTAX,
 * spread do Kage e IOF. É a mesma conta que a compra faz (com a cotação do dia dela).
 */
@RestController
public class FxQuoteController {

    private final ExchangeRateProvider exchangeRates;

    public FxQuoteController(ExchangeRateProvider exchangeRates) {
        this.exchangeRates = exchangeRates;
    }

    @GetMapping("/api/cards/international-quote")
    public ResponseEntity<InternationalPurchasePolicy.Quote> quote(@RequestParam String currency,
                                                                   @RequestParam BigDecimal amount) {
        String code = InternationalPurchasePolicy.currencyOf(currency);
        if (code == null) throw new ValidationException("Cotação é para moeda estrangeira: USD, EUR ou GBP");
        return ResponseEntity.ok(InternationalPurchasePolicy.quote(code, amount,
                exchangeRates.sellingRate(code, DomainTime.today())));
    }
}
