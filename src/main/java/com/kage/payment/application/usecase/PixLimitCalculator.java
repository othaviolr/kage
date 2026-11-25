package com.kage.payment.application.usecase;

import com.kage.payment.domain.repository.PixTransactionRepository;
import com.kage.payment.domain.service.PixLimitUsage;
import com.kage.payment.domain.service.PixNightWindow;
import com.kage.shared.domain.valueobject.Money;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

/** Quanto do limite de PIX a conta já consumiu agora (dia e janela noturna). Usado pelo envio imediato e pelo agendado. */
class PixLimitCalculator {

    private final PixTransactionRepository pixTransactionRepository;
    private final Clock clock;

    PixLimitCalculator(PixTransactionRepository pixTransactionRepository, Clock clock) {
        this.pixTransactionRepository = pixTransactionRepository;
        this.clock = clock;
    }

    PixLimitUsage usageOf(UUID sourceAccountId) {
        LocalDateTime now = LocalDateTime.now(clock);
        Money sentToday = pixTransactionRepository.sumAmountSentSince(sourceAccountId, now.toLocalDate().atStartOfDay());

        boolean night = PixNightWindow.isNight(now);
        Money sentTonight = night
                ? pixTransactionRepository.sumAmountSentSince(sourceAccountId, PixNightWindow.startOfCurrentNight(now))
                : Money.ZERO;

        return new PixLimitUsage(sentToday, sentTonight, night);
    }
}
