package com.kage.payment.infrastructure.messaging;

import com.kage.payment.domain.entity.PixRefund;
import com.kage.payment.domain.entity.PixTransaction;
import com.kage.payment.domain.enums.RefundStatus;
import com.kage.payment.domain.repository.PixRefundRepository;
import com.kage.payment.domain.enums.TransactionType;
import com.kage.payment.domain.repository.PixTransactionRepository;
import com.kage.payment.infrastructure.persistence.idempotency.ProcessedEventRepository;
import com.kage.shared.domain.valueobject.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Cobre o Inbox pattern do lado Payment: reentrega da confirmação de débito não pode
 * chamar transaction.complete() duas vezes (isso já quebrava com DomainException antes
 * dessa proteção existir, já que o método não é idempotente por natureza).
 */
@ExtendWith(MockitoExtension.class)
class PixEventConsumerTest {

    @Mock
    PixTransactionRepository pixTransactionRepository;

    @Mock
    PixRefundRepository pixRefundRepository;

    @Mock
    ProcessedEventRepository processedEventRepository;

    private PixRefund approvedRefund() {
        PixRefund refund = PixRefund.create(UUID.randomUUID(), "cobrança em duplicidade", Money.of("50.00"));
        refund.approve("admin");
        return refund;
    }

    @Test
    void onPixRefundCompleted_deveConcluirEstorno() {
        PixEventConsumer consumer = new PixEventConsumer(pixTransactionRepository, pixRefundRepository, processedEventRepository);
        PixRefund refund = approvedRefund();
        when(processedEventRepository.tryMarkAsProcessed(refund.getRefundId())).thenReturn(true);
        when(pixRefundRepository.findById(refund.getRefundId())).thenReturn(Optional.of(refund));

        consumer.onPixRefundCompleted(refund.getRefundId());

        assertThat(refund.getRefundStatus()).isEqualTo(RefundStatus.COMPLETED);
        verify(pixRefundRepository).save(refund);
    }

    @Test
    void onPixRefundFailed_deveMarcarEstornoComoFalhaComMotivo() {
        PixEventConsumer consumer = new PixEventConsumer(pixTransactionRepository, pixRefundRepository, processedEventRepository);
        PixRefund refund = approvedRefund();
        when(processedEventRepository.tryMarkAsProcessed(refund.getRefundId())).thenReturn(true);
        when(pixRefundRepository.findById(refund.getRefundId())).thenReturn(Optional.of(refund));

        consumer.onPixRefundFailed(new PixRefundFailedEvent(refund.getRefundId(), "Saldo disponível insuficiente"));

        assertThat(refund.getRefundStatus()).isEqualTo(RefundStatus.FAILED);
        assertThat(refund.getFailureReason()).isEqualTo("Saldo disponível insuficiente");
        verify(pixRefundRepository).save(refund);
    }

    @Test
    void onPixRefundCompleted_deveIgnorar_quandoReentrega() {
        PixEventConsumer consumer = new PixEventConsumer(pixTransactionRepository, pixRefundRepository, processedEventRepository);
        UUID refundId = UUID.randomUUID();
        when(processedEventRepository.tryMarkAsProcessed(refundId)).thenReturn(false);

        consumer.onPixRefundCompleted(refundId);

        verifyNoInteractions(pixRefundRepository);
    }

    @Test
    void onPixDebitConfirmed_deveCompletarTransacao_quandoPrimeiraEntrega() {
        PixEventConsumer consumer = new PixEventConsumer(pixTransactionRepository, pixRefundRepository, processedEventRepository);

        UUID transactionId = UUID.randomUUID();
        PixTransaction transaction = PixTransaction.create(UUID.randomUUID(), "chave-destino", Money.of("50.00"), "teste", TransactionType.IMMEDIATE, null);
        transaction.startProcessing(UUID.randomUUID());

        when(processedEventRepository.tryMarkAsProcessed(transactionId)).thenReturn(true);
        when(pixTransactionRepository.findById(transactionId)).thenReturn(Optional.of(transaction));

        consumer.onPixDebitConfirmed(transactionId);

        assertThat(transaction.getStatus().name()).isEqualTo("COMPLETED");
        verify(pixTransactionRepository).save(transaction);
    }

    @Test
    void onPixDebitFailed_deveMarcarTransacaoComoFalha_quandoPrimeiraEntrega() {
        PixEventConsumer consumer = new PixEventConsumer(pixTransactionRepository, pixRefundRepository, processedEventRepository);

        UUID transactionId = UUID.randomUUID();
        PixTransaction transaction = PixTransaction.create(UUID.randomUUID(), "chave-destino", Money.of("50.00"), "teste", TransactionType.IMMEDIATE, null);
        transaction.startProcessing(UUID.randomUUID());

        when(processedEventRepository.tryMarkAsProcessed(transactionId)).thenReturn(true);
        when(pixTransactionRepository.findById(transactionId)).thenReturn(Optional.of(transaction));

        consumer.onPixDebitFailed(new PixDebitFailedEvent(transactionId, "Saldo disponível insuficiente"));

        assertThat(transaction.getStatus().name()).isEqualTo("FAILED");
        assertThat(transaction.getFailureReason()).isEqualTo("Saldo disponível insuficiente");
        verify(pixTransactionRepository).save(transaction);
    }

    @Test
    void onPixDebitFailed_deveIgnorar_quandoEventoJaProcessado() {
        PixEventConsumer consumer = new PixEventConsumer(pixTransactionRepository, pixRefundRepository, processedEventRepository);
        UUID transactionId = UUID.randomUUID();

        when(processedEventRepository.tryMarkAsProcessed(transactionId)).thenReturn(false);

        consumer.onPixDebitFailed(new PixDebitFailedEvent(transactionId, "qualquer"));

        verifyNoInteractions(pixTransactionRepository);
    }

    @Test
    void onPixDebitConfirmed_deveIgnorar_quandoEventoJaProcessado() {
        PixEventConsumer consumer = new PixEventConsumer(pixTransactionRepository, pixRefundRepository, processedEventRepository);
        UUID transactionId = UUID.randomUUID();

        when(processedEventRepository.tryMarkAsProcessed(transactionId)).thenReturn(false);

        consumer.onPixDebitConfirmed(transactionId);

        verifyNoInteractions(pixTransactionRepository);
    }
}
