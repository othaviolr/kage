package com.kage.payment.infrastructure.messaging;

import com.kage.payment.application.usecase.PixCharges;
import com.kage.payment.domain.entity.PixRefund;
import com.kage.payment.domain.entity.PixTransaction;
import com.kage.payment.domain.repository.PixRefundRepository;
import com.kage.payment.domain.repository.PixTransactionRepository;
import com.kage.payment.infrastructure.persistence.idempotency.ProcessedEventRepository;
import com.kage.shared.domain.exception.DomainException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

public class PixEventConsumer {

    private static final Logger logger = LoggerFactory.getLogger(PixEventConsumer.class);

    private final PixTransactionRepository pixTransactionRepository;
    private final PixRefundRepository pixRefundRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final PixCharges pixCharges;

    public PixEventConsumer(PixTransactionRepository pixTransactionRepository,
                            PixRefundRepository pixRefundRepository,
                            ProcessedEventRepository processedEventRepository) {
        this(pixTransactionRepository, pixRefundRepository, processedEventRepository, null);
    }

    /** @param pixCharges avisa quem cobrou quando o PIX que pagou a cobrança conclui */
    public PixEventConsumer(PixTransactionRepository pixTransactionRepository,
                            PixRefundRepository pixRefundRepository,
                            ProcessedEventRepository processedEventRepository, PixCharges pixCharges) {
        this.pixCharges = pixCharges;
        this.pixTransactionRepository = pixTransactionRepository;
        this.pixRefundRepository = pixRefundRepository;
        this.processedEventRepository = processedEventRepository;
    }

    @RabbitListener(queues = "pix.debit.confirmed.queue")
    @Transactional
    public void onPixDebitConfirmed(UUID transactionId) {
        if (!processedEventRepository.tryMarkAsProcessed(transactionId)) {
            logger.warn("Evento de confirmação PIX {} já processado, ignorando reentrega", transactionId);
            return;
        }

        PixTransaction transaction = pixTransactionRepository.findById(transactionId)
                .orElseThrow(() -> new DomainException("Transação PIX não encontrada"));

        transaction.complete();
        pixTransactionRepository.save(transaction);
        if (pixCharges != null) pixCharges.paymentCompleted(transactionId);
    }

    /**
     * Uma transação tem um único desfecho (confirmada ou falha), então as duas filas compartilham a
     * mesma chave de idempotência: o transactionId.
     */
    @RabbitListener(queues = "pix.debit.failed.queue")
    @Transactional
    public void onPixDebitFailed(PixDebitFailedEvent event) {
        if (!processedEventRepository.tryMarkAsProcessed(event.transactionId())) {
            logger.warn("Evento de falha PIX {} já processado, ignorando reentrega", event.transactionId());
            return;
        }

        PixTransaction transaction = pixTransactionRepository.findById(event.transactionId())
                .orElseThrow(() -> new DomainException("Transação PIX não encontrada"));

        transaction.fail(event.reason());
        pixTransactionRepository.save(transaction);
    }

    /** Mesma ideia do PIX: um estorno tem um único desfecho, a chave de idempotência é o refundId. */
    @RabbitListener(queues = "pix.refund.completed.queue")
    @Transactional
    public void onPixRefundCompleted(UUID refundId) {
        if (!processedEventRepository.tryMarkAsProcessed(refundId)) {
            logger.warn("Conclusão de estorno PIX {} já processada, ignorando reentrega", refundId);
            return;
        }

        PixRefund refund = pixRefundRepository.findById(refundId)
                .orElseThrow(() -> new DomainException("Estorno PIX não encontrado"));

        refund.complete();
        pixRefundRepository.save(refund);
    }

    @RabbitListener(queues = "pix.refund.failed.queue")
    @Transactional
    public void onPixRefundFailed(PixRefundFailedEvent event) {
        if (!processedEventRepository.tryMarkAsProcessed(event.refundId())) {
            logger.warn("Falha de estorno PIX {} já processada, ignorando reentrega", event.refundId());
            return;
        }

        PixRefund refund = pixRefundRepository.findById(event.refundId())
                .orElseThrow(() -> new DomainException("Estorno PIX não encontrado"));

        refund.fail(event.reason());
        pixRefundRepository.save(refund);
    }
}