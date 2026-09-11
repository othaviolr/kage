package com.kage.account.infrastructure.messaging;

import com.kage.account.domain.entity.Account;
import com.kage.account.domain.enums.AccountType;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.account.infrastructure.persistence.idempotency.ProcessedEventRepository;
import com.kage.shared.domain.valueobject.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Cobre o Inbox pattern do lado Account: a reentrega de um PixSentEvent com o mesmo
 * transactionId não pode debitar/creditar duas vezes.
 */
@ExtendWith(MockitoExtension.class)
class AccountPixEventConsumerTest {

    @Mock
    AccountRepository accountRepository;

    @Mock
    ProcessedEventRepository processedEventRepository;

    @Mock
    RabbitTemplate rabbitTemplate;

    @InjectMocks
    AccountPixEventConsumer consumer;

    private UUID transactionId;
    private Account source;
    private Account target;
    private PixSentEvent event;

    @BeforeEach
    void setUp() {
        transactionId = UUID.randomUUID();
        source = Account.create(UUID.randomUUID(), AccountType.CHECKING, "00001", "1");
        source.credit(Money.of("500.00"));
        target = Account.create(UUID.randomUUID(), AccountType.CHECKING, "00002", "2");

        event = new PixSentEvent(transactionId, source.getId(), "chave-destino", target.getId(),
                new BigDecimal("100.00"), "PROCESSING", "E2E123", "2026-09-11T10:00:00");
    }

    @Test
    void onPixSent_deveDebitarOrigemECreditarDestino_quandoPrimeiraEntrega() {
        when(processedEventRepository.tryMarkAsProcessed(transactionId)).thenReturn(true);
        when(accountRepository.findById(source.getId())).thenReturn(Optional.of(source));
        when(accountRepository.findById(target.getId())).thenReturn(Optional.of(target));

        consumer.onPixSent(event);

        assertThat(source.getBalance().amount()).isEqualByComparingTo("400.00");
        assertThat(target.getBalance().amount()).isEqualByComparingTo("100.00");
        verify(accountRepository).save(source);
        verify(accountRepository).save(target);
        verify(rabbitTemplate).convertAndSend("pix.exchange", "pix.debit.confirmed", transactionId);
    }

    @Test
    void onPixSent_deveIgnorarENaoDebitar_quandoEventoJaProcessado() {
        when(processedEventRepository.tryMarkAsProcessed(transactionId)).thenReturn(false);

        consumer.onPixSent(event);

        verifyNoInteractions(accountRepository);
        verifyNoInteractions(rabbitTemplate);
    }
}
