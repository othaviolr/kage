package com.kage.payment.application.usecase;

import com.kage.payment.domain.entity.PixKey;
import com.kage.payment.domain.entity.PixTransaction;
import com.kage.payment.domain.enums.PixKeyType;
import com.kage.payment.domain.repository.PixKeyRepository;
import com.kage.payment.domain.repository.PixTransactionRepository;
import com.kage.payment.domain.service.AccountValidationService;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.valueobject.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

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

    @InjectMocks
    SendPix sendPix;

    private UUID sourceAccountId;
    private UUID targetAccountId;
    private PixKey targetKey;

    @BeforeEach
    void setUp() {
        sourceAccountId = UUID.randomUUID();
        targetAccountId = UUID.randomUUID();
        targetKey = PixKey.create(targetAccountId, PixKeyType.RANDOM, "chave-destino");
    }

    @Test
    void execute_deveCriarTransacaoEPublicarEvento_quandoDadosValidos() {
        when(pixKeyRepository.findByKeyValue("chave-destino")).thenReturn(Optional.of(targetKey));
        when(pixTransactionRepository.save(any(PixTransaction.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SendPix.Output output = sendPix.execute(new SendPix.Input(sourceAccountId, "chave-destino", Money.of("150.00"), "teste"));

        assertThat(output.sourceAccountId()).isEqualTo(sourceAccountId);
        assertThat(output.targetAccountId()).isEqualTo(targetAccountId);
        assertThat(output.status()).isEqualTo("PROCESSING");
        verify(accountValidationService).validateBalanceAndLimits(sourceAccountId, Money.of("150.00"));
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
                .when(accountValidationService).validateBalanceAndLimits(sourceAccountId, Money.of("9999.00"));

        assertThatThrownBy(() -> sendPix.execute(new SendPix.Input(sourceAccountId, "chave-destino", Money.of("9999.00"), null)))
                .isInstanceOf(BusinessRuleException.class);

        verifyNoInteractions(pixTransactionRepository, pixEventPublisher);
    }
}
