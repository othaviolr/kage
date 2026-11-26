package com.kage.payment.application.usecase;

import com.kage.payment.domain.repository.PixTransactionRepository;
import com.kage.payment.domain.service.PixLimitUsage;
import com.kage.payment.domain.service.PixNightWindow;
import com.kage.shared.domain.valueobject.Money;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Quanto do limite de PIX a conta já consumiu agora (dia e janela noturna da conta). Usado pelo envio
 * imediato e pelo agendado.
 */
class PixLimitCalculator {

    private final PixTransactionRepository pixTransactionRepository;
    private final Clock clock;

    PixLimitCalculator(PixTransactionRepository pixTransactionRepository, Clock clock) {
        this.pixTransactionRepository = pixTransactionRepository;
        this.clock = clock;
    }

    /** @param nightStartHour início da janela noturna escolhido pelo cliente (20h a 22h) */
    PixLimitUsage usageOf(UUID sourceAccountId, int nightStartHour) {
        LocalDateTime now = LocalDateTime.now(clock);
        Money sentToday = pixTransactionRepository.sumAmountSentSince(sourceAccountId, now.toLocalDate().atStartOfDay());

        boolean night = PixNightWindow.isNight(now, nightStartHour);
        Money sentTonight = night
                ? pixTransactionRepository.sumAmountSentSince(sourceAccountId, PixNightWindow.startOfCurrentNight(now, nightStartHour))
                : Money.ZERO;

        return new PixLimitUsage(sentToday, sentTonight, night);
    }
}
