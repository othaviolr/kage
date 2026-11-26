package com.kage.payment.application.usecase;

import com.kage.payment.domain.entity.PixAutomaticAuthorization;
import com.kage.payment.domain.entity.PixAutomaticCharge;
import com.kage.payment.domain.entity.PixRefund;
import com.kage.payment.domain.entity.PixTransaction;

/** Porta de saída do PIX. A implementação grava no outbox: chamar dentro da transação do use case. */
public interface PixEventPublisher {

    void publishPixSent(SendPix.Output output);

    /** Pede ao Account que devolva o valor do estorno de quem recebeu o PIX original para quem pagou. */
    void publishRefundApproved(PixRefund refund, PixTransaction originalTransaction);

    /** O PIX agendado não pôde ser executado no dia (aviso ao cliente). */
    void publishScheduledPixFailed(PixTransaction transaction, String reason);

    /** Quem recebe pediu uma autorização de PIX Automático (aviso ao pagador para responder). */
    void publishPixAutomaticRequested(PixAutomaticAuthorization authorization);

    /**
     * Uma tentativa de pagamento de uma cobrança foi agendada (aviso ao pagador, que pode cancelar até a
     * véspera). {@code attempt} 1 é a do vencimento; maior que 1, uma nova tentativa depois de recusada.
     */
    void publishPixAutomaticChargeScheduled(PixAutomaticAuthorization authorization, PixAutomaticCharge charge,
                                            int attempt, java.time.LocalDate scheduledDate);
}
