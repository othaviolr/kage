package com.kage.payment.application.usecase;

import com.kage.payment.domain.entity.PixKey;
import com.kage.payment.domain.entity.PixTransaction;
import com.kage.payment.domain.enums.PixKeyType;
import com.kage.payment.domain.repository.PixKeyRepository;
import com.kage.payment.domain.repository.PixTransactionRepository;
import com.kage.payment.domain.service.AccountValidationService;
import com.kage.payment.domain.service.PixLimitUsage;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.valueobject.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SendPixTest {

    @Mock
    PixKeyRepository pixKeyRepository;

    @Mock
    PixTransactionRepository pixTransactionRepository;

    @Mock
    AccountValidationService accountValidationService;

    @Mock
    PixEventPublisher pixEventPublisher;

    SendPix sendPix;

    private UUID sourceAccountId;
    private UUID targetAccountId;
    private PixKey targetKey;

    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

    private SendPix sendPixAt(LocalDateTime now) {
        Clock clock = Clock.fixed(now.atZone(ZONE).toInstant(), ZONE);
        return new SendPix(pixKeyRepository, pixTransactionRepository, accountValidationService, pixEventPublisher, clock);
    }

    @BeforeEach
    void setUp() {
        sourceAccountId = UUID.randomUUID();
        targetAccountId = UUID.randomUUID();
        targetKey = PixKey.create(targetAccountId, PixKeyType.RANDOM, "chave-destino");
        sendPix = sendPixAt(LocalDateTime.of(2026, 11, 25, 14, 0));
    }

    @SuppressWarnings("unchecked")
    private PixLimitUsage capturedUsage() {
        ArgumentCaptor<Supplier<PixLimitUsage>> usage = ArgumentCaptor.forClass(Supplier.class);
        verify(accountValidationService).validateBalanceAndLimits(eq(sourceAccountId), any(), usage.capture());
        return usage.getValue().get();
    }

    @Test
    void execute_deveCalcularUsoDoDia_semJanelaNoturna_duranteODia() {
        when(pixKeyRepository.findByKeyValue("chave-destino")).thenReturn(Optional.of(targetKey));
        when(pixTransactionRepository.save(any(PixTransaction.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(pixTransactionRepository.sumAmountSentSince(sourceAccountId, LocalDateTime.of(2026, 11, 25, 0, 0)))
                .thenReturn(Money.of("700.00"));

        sendPix.execute(new SendPix.Input(sourceAccountId, "chave-destino", Money.of("10.00"), null));

        PixLimitUsage usage = capturedUsage();
        assertThat(usage.sentToday()).isEqualTo(Money.of("700.00"));
        assertThat(usage.nightTime()).isFalse();
        assertThat(usage.sentTonight()).isEqualTo(Money.ZERO);
    }

    @Test
    void execute_deveSomarDesdeAs20hDeOntem_quandoEnviadoDeMadrugada() {
        SendPix madrugada = sendPixAt(LocalDateTime.of(2026, 11, 25, 2, 30));
        when(pixKeyRepository.findByKeyValue("chave-destino")).thenReturn(Optional.of(targetKey));
        when(pixTransactionRepository.save(any(PixTransaction.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(pixTransactionRepository.sumAmountSentSince(sourceAccountId, LocalDateTime.of(2026, 11, 25, 0, 0)))
                .thenReturn(Money.of("100.00"));
        when(pixTransactionRepository.sumAmountSentSince(sourceAccountId, LocalDateTime.of(2026, 11, 24, 20, 0)))
                .thenReturn(Money.of("400.00"));

        madrugada.execute(new SendPix.Input(sourceAccountId, "chave-destino", Money.of("10.00"), null));

        PixLimitUsage usage = capturedUsage();
        assertThat(usage.nightTime()).isTrue();
        assertThat(usage.sentToday()).isEqualTo(Money.of("100.00"));
        assertThat(usage.sentTonight()).isEqualTo(Money.of("400.00"));
    }

    @Test
    void execute_deveCriarTransacaoEPublicarEvento_quandoDadosValidos() {
        when(pixKeyRepository.findByKeyValue("chave-destino")).thenReturn(Optional.of(targetKey));
        when(pixTransactionRepository.save(any(PixTransaction.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SendPix.Output output = sendPix.execute(new SendPix.Input(sourceAccountId, "chave-destino", Money.of("150.00"), "teste"));

        assertThat(output.sourceAccountId()).isEqualTo(sourceAccountId);
        assertThat(output.targetAccountId()).isEqualTo(targetAccountId);
        assertThat(output.status()).isEqualTo("PROCESSING");
        verify(accountValidationService).validateBalanceAndLimits(eq(sourceAccountId), eq(Money.of("150.00")), any());
        verify(pixEventPublisher).publishPixSent(output);
    }

    @Test
    void execute_deveLancarNotFoundException_quandoChavePixNaoEncontrada() {
        when(pixKeyRepository.findByKeyValue("inexistente")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sendPix.execute(new SendPix.Input(sourceAccountId, "inexistente", Money.of("10.00"), null)))
                .isInstanceOf(NotFoundException.class);

        verifyNoInteractions(accountValidationService, pixEventPublisher);
    }

    @Test
    void execute_deveLancarBusinessRuleException_quandoDestinoEhAPropriaConta() {
        PixKey ownKey = PixKey.create(sourceAccountId, PixKeyType.RANDOM, "minha-chave");
        when(pixKeyRepository.findByKeyValue("minha-chave")).thenReturn(Optional.of(ownKey));

        assertThatThrownBy(() -> sendPix.execute(new SendPix.Input(sourceAccountId, "minha-chave", Money.of("10.00"), null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("própria conta");

        verifyNoInteractions(pixEventPublisher);
    }

    @Test
    void execute_devePropagarBusinessRuleException_quandoValidacaoDeSaldoOuLimiteFalha() {
        when(pixKeyRepository.findByKeyValue("chave-destino")).thenReturn(Optional.of(targetKey));
        doThrow(new BusinessRuleException("Saldo insuficiente para realizar o PIX"))
                .when(accountValidationService).validateBalanceAndLimits(eq(sourceAccountId), eq(Money.of("9999.00")), any());

        assertThatThrownBy(() -> sendPix.execute(new SendPix.Input(sourceAccountId, "chave-destino", Money.of("9999.00"), null)))
                .isInstanceOf(BusinessRuleException.class);

        verifyNoInteractions(pixTransactionRepository, pixEventPublisher);
    }
}
