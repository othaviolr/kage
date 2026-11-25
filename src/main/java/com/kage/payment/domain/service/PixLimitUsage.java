package com.kage.payment.domain.service;

import com.kage.shared.domain.valueobject.Money;

/**
 * Quanto do limite de PIX a conta já consumiu no momento do envio.
 *
 * @param sentToday   PIX enviados desde 00h (exceto falhos/cancelados)
 * @param sentTonight PIX enviados na janela noturna atual (zero fora dela)
 * @param nightTime   se o envio acontece na janela noturna
 */
public record PixLimitUsage(Money sentToday, Money sentTonight, boolean nightTime) {
}
