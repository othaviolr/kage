package com.kage.payment.infrastructure.messaging;

import com.kage.payment.domain.entity.PixTransaction;
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
    ProcessedEventRepository processedEventRepository;

    @Test
    void onPixDebitConfirmed_deveCompletarTransacao_quandoPrimeiraEntrega() {
        PixEventConsumer consumer = new PixEventConsumer(pixTransactionRepository, processedEventRepository);

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
    void onPixDebitConfirmed_deveIgnorar_quandoEventoJaProcessado() {
        PixEventConsumer consumer = new PixEventConsumer(pixTransactionRepository, processedEventRepository);
        UUID transactionId = UUID.randomUUID();

        when(processedEventRepository.tryMarkAsProcessed(transactionId)).thenReturn(false);

        consumer.onPixDebitConfirmed(transactionId);

        verifyNoInteractions(pixTransactionRepository);
    }
}
