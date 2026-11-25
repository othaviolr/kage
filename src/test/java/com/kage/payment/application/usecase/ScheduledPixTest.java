package com.kage.payment.application.usecase;

import com.kage.payment.domain.entity.PixKey;
import com.kage.payment.domain.entity.PixTransaction;
import com.kage.payment.domain.enums.PixKeyType;
import com.kage.payment.domain.enums.PixTransactionStatus;
import com.kage.payment.domain.repository.PixKeyRepository;
import com.kage.payment.domain.repository.PixTransactionRepository;
import com.kage.payment.domain.service.AccountValidationService;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.DomainException;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.valueobject.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Agendamento (regras do domínio), cancelamento e execução de PIX agendado. */
@ExtendWith(MockitoExtension.class)
class ScheduledPixTest {

    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 25);

    @Mock PixKeyRepository pixKeyRepository;
    @Mock PixTransactionRepository pixTransactionRepository;
    @Mock AccountValidationService accountValidationService;
    @Mock PixEventPublisher pixEventPublisher;
    @Mock PixRecurrenceChain recurrenceChain;

    Clock clock;
    UUID source;
    UUID target;
    PixKey targetKey;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(TODAY.atTime(6, 0).atZone(ZONE).toInstant(), ZONE);
        source = UUID.randomUUID();
        target = UUID.randomUUID();
        targetKey = PixKey.create(target, PixKeyType.RANDOM, "chave-destino");
    }

    private PixTransaction scheduled(LocalDate date) {
        return PixTransaction.schedule(source, "chave-destino", Money.of("100.00"), "aluguel", date, date.minusDays(1));
    }

    // ---------- domínio ----------

    @Test
    void agendar_deveExigirDataFutura_eNoMaximoUmAno() {
        assertThatThrownBy(() -> PixTransaction.schedule(source, "k", Money.of("1.00"), null, TODAY, TODAY))
                .isInstanceOf(DomainException.class).hasMessageContaining("PIX imediato");
        assertThatThrownBy(() -> PixTransaction.schedule(source, "k", Money.of("1.00"), null, TODAY.plusDays(366), TODAY))
                .isInstanceOf(DomainException.class).hasMessageContaining("365");
        assertThatThrownBy(() -> PixTransaction.schedule(source, "k", Money.ZERO, null, TODAY.plusDays(1), TODAY))
                .isInstanceOf(DomainException.class);

        PixTransaction ok = PixTransaction.schedule(source, "k", Money.of("1.00"), null, TODAY.plusDays(365), TODAY);
        assertThat(ok.getStatus()).isEqualTo(PixTransactionStatus.PENDING);
        assertThat(ok.isScheduled()).isTrue();
    }

    @Test
    void agendar_naoConfereSaldo_soAChave() {
        when(pixKeyRepository.findByKeyValue("chave-destino")).thenReturn(Optional.of(targetKey));
        when(pixTransactionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        SchedulePix.Output output = new SchedulePix(pixKeyRepository, pixTransactionRepository, clock)
                .execute(new SchedulePix.Input(source, "chave-destino", Money.of("100.00"), "aluguel", TODAY.plusDays(5)));

        assertThat(output.status()).isEqualTo("PENDING");
        assertThat(output.scheduledDate()).isEqualTo(TODAY.plusDays(5));
        verifyNoInteractions(accountValidationService, pixEventPublisher);
    }

    @Test
    void cancelar_soFuncionaParaAgendadoPendente() {
        PixTransaction pending = scheduled(TODAY.plusDays(3));
        when(pixTransactionRepository.findById(pending.getTransactionId())).thenReturn(Optional.of(pending));
        when(pixTransactionRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        ManageScheduledPix manage = new ManageScheduledPix(pixTransactionRepository, recurrenceChain);

        assertThat(manage.cancel(pending.getTransactionId()).status()).isEqualTo("CANCELLED");

        PixTransaction immediate = PixTransaction.create(source, "k", Money.of("1.00"), null,
                com.kage.payment.domain.enums.TransactionType.IMMEDIATE, null);
        when(pixTransactionRepository.findById(immediate.getTransactionId())).thenReturn(Optional.of(immediate));
        assertThatThrownBy(() -> manage.cancel(immediate.getTransactionId())).isInstanceOf(NotFoundException.class);
    }

    // ---------- execução ----------

    private ScheduledPixExecutor executor() {
        return new ScheduledPixExecutor(pixKeyRepository, pixTransactionRepository, accountValidationService, pixEventPublisher,
                recurrenceChain, clock);
    }

    @Test
    void executar_deveEnviarPeloMesmoCaminhoDoImediato() {
        PixTransaction pix = scheduled(TODAY);
        when(pixTransactionRepository.findById(pix.getTransactionId())).thenReturn(Optional.of(pix));
        when(pixKeyRepository.findByKeyValue("chave-destino")).thenReturn(Optional.of(targetKey));
        when(pixTransactionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        assertThat(executor().execute(pix.getTransactionId())).isEqualTo(ScheduledPixExecutor.Result.SENT);

        assertThat(pix.getStatus()).isEqualTo(PixTransactionStatus.PROCESSING);
        assertThat(pix.getTargetAccountId()).isEqualTo(target);
        verify(accountValidationService).validateBalanceAndLimits(eq(source), eq(Money.of("100.00")), any());
        verify(pixEventPublisher).publishPixSent(any());
    }

    @Test
    void executar_semSaldo_deveFalharComMotivo_eAvisar() {
        PixTransaction pix = scheduled(TODAY);
        when(pixTransactionRepository.findById(pix.getTransactionId())).thenReturn(Optional.of(pix));
        when(pixKeyRepository.findByKeyValue("chave-destino")).thenReturn(Optional.of(targetKey));
        doThrow(new BusinessRuleException("Saldo insuficiente para realizar o PIX"))
                .when(accountValidationService).validateBalanceAndLimits(any(), any(), any());

        assertThat(executor().execute(pix.getTransactionId())).isEqualTo(ScheduledPixExecutor.Result.FAILED);

        assertThat(pix.getStatus()).isEqualTo(PixTransactionStatus.FAILED);
        assertThat(pix.getFailureReason()).contains("Saldo insuficiente");
        verify(pixEventPublisher).publishScheduledPixFailed(pix, "Saldo insuficiente para realizar o PIX");
        verify(pixEventPublisher, never()).publishPixSent(any());
    }

    @Test
    void executar_comChaveRemovidaAteODia_deveFalhar() {
        PixTransaction pix = scheduled(TODAY);
        targetKey.delete();
        when(pixTransactionRepository.findById(pix.getTransactionId())).thenReturn(Optional.of(pix));
        when(pixKeyRepository.findByKeyValue("chave-destino")).thenReturn(Optional.of(targetKey));

        assertThat(executor().execute(pix.getTransactionId())).isEqualTo(ScheduledPixExecutor.Result.FAILED);
        assertThat(pix.getFailureReason()).contains("não existe mais");
        verifyNoInteractions(accountValidationService);
    }

    @Test
    void executar_deveIgnorar_quandoJaNaoEstaPendente() {
        PixTransaction pix = scheduled(TODAY);
        pix.cancel();
        when(pixTransactionRepository.findById(pix.getTransactionId())).thenReturn(Optional.of(pix));

        assertThat(executor().execute(pix.getTransactionId())).isEqualTo(ScheduledPixExecutor.Result.SKIPPED);
        verifyNoInteractions(pixKeyRepository, accountValidationService, pixEventPublisher);
    }

    @Test
    void rodada_deveContarCadaResultado() {
        PixTransaction a = scheduled(TODAY);
        PixTransaction b = scheduled(TODAY);
        ScheduledPixExecutor executor = mock(ScheduledPixExecutor.class);
        when(pixTransactionRepository.findScheduledDueUntil(TODAY)).thenReturn(java.util.List.of(a, b));
        when(executor.execute(a.getTransactionId())).thenReturn(ScheduledPixExecutor.Result.SENT);
        when(executor.execute(b.getTransactionId())).thenReturn(ScheduledPixExecutor.Result.FAILED);

        RunScheduledPix.Output output = new RunScheduledPix(pixTransactionRepository, executor).execute(TODAY);

        assertThat(output.sent()).isEqualTo(1);
        assertThat(output.failed()).isEqualTo(1);
    }
}
