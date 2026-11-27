package com.kage.card.application.usecase;

import com.kage.card.domain.entity.Card;
import com.kage.card.domain.repository.LimitAlertRepository;
import com.kage.card.domain.service.LimitUsagePolicy;
import com.kage.shared.domain.DomainTime;

import java.time.YearMonth;
import java.util.List;

/**
 * Aviso de uso do limite depois de uma compra aprovada: ao atingir 80% e ao atingir 100%, cada faixa
 * no máximo uma vez por fatura (a da compra). Se a compra pula direto para 100%, só esse aviso sai —
 * os 80% ficam marcados para não chegarem depois, fora de ordem.
 *
 * Chamar dentro da transação da compra: a marcação e o evento vão juntos com ela.
 */
public class LimitUsageAlerts {

    private final LimitAlertRepository repository;
    private final CardEventPublisher eventPublisher;

    public LimitUsageAlerts(LimitAlertRepository repository, CardEventPublisher eventPublisher) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
    }

    public void afterPurchase(Card card, YearMonth referenceMonth) {
        List<Integer> reached = LimitUsagePolicy.reached(card.getUsedLimit(), card.getCreditLimit());
        Integer toAlert = null;
        for (int threshold : reached) {
            if (repository.markAlerted(card.getId(), referenceMonth, threshold, DomainTime.now())) {
                toAlert = threshold; // a maior faixa nova
            }
        }
        if (toAlert == null) return;
        eventPublisher.publishLimitUsageAlert(card.getId(), referenceMonth.toString(), toAlert,
                LimitUsagePolicy.usagePercent(card.getUsedLimit(), card.getCreditLimit()), card.getUsedLimit(),
                card.getCreditLimit(), card.getApprovedLimit(), card.availableLimit());
    }
}
