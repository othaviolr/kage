package com.kage.account.infrastructure.messaging;

import com.kage.account.domain.entity.Account;
import com.kage.account.domain.enums.AccountType;
import com.kage.account.domain.enums.EntryType;
import com.kage.account.domain.repository.AccountEntryRepository;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.account.infrastructure.persistence.idempotency.ProcessedEventRepository;
import com.kage.shared.domain.valueobject.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.kage.shared.infrastructure.messaging.OutboxEventPublisher;

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
    AccountEntryRepository entryRepository;

    @Mock
    ProcessedEventRepository processedEventRepository;

    @Mock
    OutboxEventPublisher outbox;

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
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

        consumer.onPixSent(event);

        assertThat(source.getBalance().amount()).isEqualByComparingTo("400.00");
        assertThat(target.getBalance().amount()).isEqualByComparingTo("100.00");
        verify(accountRepository).save(source);
        verify(accountRepository).save(target);
        verify(entryRepository).save(argThat(e -> e.accountId().equals(source.getId())
                && e.type() == EntryType.PIX_SENT && e.referenceId().equals(transactionId)));
        verify(entryRepository).save(argThat(e -> e.accountId().equals(target.getId())
                && e.type() == EntryType.PIX_RECEIVED && e.balanceAfter().equals(Money.of("100.00"))));
        verify(outbox).publish("pix.exchange", "pix.debit.confirmed", transactionId);
    }

    @Test
    void onPixSent_devePublicarFalhaENaoSalvar_quandoSaldoInsuficiente() {
        PixSentEvent semSaldo = new PixSentEvent(transactionId, source.getId(), "chave-destino", target.getId(),
                new BigDecimal("900.00"), "PROCESSING", "E2E123", "2026-09-11T10:00:00");
        when(processedEventRepository.tryMarkAsProcessed(transactionId)).thenReturn(true);
        when(accountRepository.findById(source.getId())).thenReturn(Optional.of(source));
        when(accountRepository.findById(target.getId())).thenReturn(Optional.of(target));

        consumer.onPixSent(semSaldo);

        verify(accountRepository, never()).save(any());
        verifyNoInteractions(entryRepository);
        verify(outbox).publish("pix.exchange", "pix.debit.failed",
                new PixDebitFailedEvent(transactionId, "Saldo disponível insuficiente"));
        verify(outbox, never()).publish("pix.exchange", "pix.debit.confirmed", transactionId);
    }

    @Test
    void onPixSent_devePublicarFalhaENaoDebitarOrigem_quandoContaDestinoBloqueada() {
        target.block();
        when(processedEventRepository.tryMarkAsProcessed(transactionId)).thenReturn(true);
        when(accountRepository.findById(source.getId())).thenReturn(Optional.of(source));
        when(accountRepository.findById(target.getId())).thenReturn(Optional.of(target));

        consumer.onPixSent(event);

        verify(accountRepository, never()).save(any());
        verify(outbox).publish(eq("pix.exchange"), eq("pix.debit.failed"), any(PixDebitFailedEvent.class));
    }

    @Test
    void onPixRefundApproved_deveDevolverDoRecebedorParaOPagador() {
        // estado depois do PIX original: source pagou 100, target recebeu 100
        target.credit(Money.of("100.00"));
        UUID refundId = UUID.randomUUID();
        when(processedEventRepository.tryMarkAsProcessed(refundId)).thenReturn(true);
        when(accountRepository.findById(source.getId())).thenReturn(Optional.of(source));
        when(accountRepository.findById(target.getId())).thenReturn(Optional.of(target));
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

        consumer.onPixRefundApproved(new PixRefundApprovedEvent(refundId, transactionId, target.getId(),
                source.getId(), new BigDecimal("40.00")));

        assertThat(target.getBalance().amount()).isEqualByComparingTo("60.00");
        assertThat(source.getBalance().amount()).isEqualByComparingTo("540.00");
        verify(entryRepository).save(argThat(e -> e.accountId().equals(target.getId())
                && e.type() == EntryType.PIX_REFUND_SENT && e.referenceId().equals(refundId)));
        verify(entryRepository).save(argThat(e -> e.accountId().equals(source.getId())
                && e.type() == EntryType.PIX_REFUND_RECEIVED));
        verify(outbox).publish("pix.exchange", "pix.refund.completed", refundId);
    }

    @Test
    void onPixRefundApproved_devePublicarFalha_quandoRecebedorJaGastouODinheiro() {
        UUID refundId = UUID.randomUUID(); // target com saldo zero
        when(processedEventRepository.tryMarkAsProcessed(refundId)).thenReturn(true);
        when(accountRepository.findById(source.getId())).thenReturn(Optional.of(source));
        when(accountRepository.findById(target.getId())).thenReturn(Optional.of(target));

        consumer.onPixRefundApproved(new PixRefundApprovedEvent(refundId, transactionId, target.getId(),
                source.getId(), new BigDecimal("40.00")));

        verify(accountRepository, never()).save(any());
        verifyNoInteractions(entryRepository);
        verify(outbox).publish("pix.exchange", "pix.refund.failed",
                new PixRefundFailedEvent(refundId, "Saldo disponível insuficiente"));
    }

    @Test
    void onPixRefundApproved_deveIgnorar_quandoReentrega() {
        UUID refundId = UUID.randomUUID();
        when(processedEventRepository.tryMarkAsProcessed(refundId)).thenReturn(false);

        consumer.onPixRefundApproved(new PixRefundApprovedEvent(refundId, transactionId, target.getId(),
                source.getId(), new BigDecimal("40.00")));

        verifyNoInteractions(accountRepository, entryRepository, outbox);
    }

    @Test
    void onPixSent_deveIgnorarENaoDebitar_quandoEventoJaProcessado() {
        when(processedEventRepository.tryMarkAsProcessed(transactionId)).thenReturn(false);

        consumer.onPixSent(event);

        verifyNoInteractions(accountRepository);
        verifyNoInteractions(outbox);
    }
}
