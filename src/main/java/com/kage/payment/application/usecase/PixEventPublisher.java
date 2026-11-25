package com.kage.payment.application.usecase;

import com.kage.payment.domain.entity.PixRefund;
import com.kage.payment.domain.entity.PixTransaction;

/** Porta de saída do PIX. A implementação grava no outbox: chamar dentro da transação do use case. */
public interface PixEventPublisher {

    void publishPixSent(SendPix.Output output);

    /** Pede ao Account que devolva o valor do estorno de quem recebeu o PIX original para quem pagou. */
    void publishRefundApproved(PixRefund refund, PixTransaction originalTransaction);
}
