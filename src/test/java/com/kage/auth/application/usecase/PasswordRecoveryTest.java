package com.kage.auth.application.usecase;

import com.kage.auth.domain.entity.Credential;
import com.kage.auth.domain.repository.CredentialRepository;
import com.kage.auth.domain.repository.PasswordResetRepository;
import com.kage.auth.domain.repository.SessionRepository;
import com.kage.auth.domain.service.AuthEventPublisher;
import com.kage.auth.domain.service.CustomerDirectory;
import com.kage.auth.domain.service.PasswordHasher;
import com.kage.auth.domain.service.PasswordResetSender;
import com.kage.auth.domain.valueobject.RefreshTokenValue;
import com.kage.shared.domain.exception.TooManyRequestsException;
import com.kage.shared.domain.exception.UnauthorizedException;
import com.kage.shared.domain.exception.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Troca, pedido de recuperação e redefinição de senha. */
@ExtendWith(MockitoExtension.class)
class PasswordRecoveryTest {

    private static final String CPF = "52998224725";

    @Mock CustomerDirectory customerDirectory;
    @Mock CredentialRepository credentialRepository;
    @Mock PasswordResetRepository resetRepository;
    @Mock SessionRepository sessionRepository;
    @Mock PasswordHasher passwordHasher;
    @Mock PasswordResetSender sender;
    @Mock AuthEventPublisher eventPublisher;

    private final SessionTokensTest.MutableClock clock = new SessionTokensTest.MutableClock(Instant.parse("2026-11-25T13:00:00Z"));
    private UUID customerId;
    private Credential credential;

    @BeforeEach
    void setUp() {
        customerId = UUID.randomUUID();
        credential = Credential.create(customerId, "old-hash", LocalDateTime.now(clock));
        lenient().when(customerDirectory.cpfDigitsOf(customerId)).thenReturn(Optional.of(CPF));
        lenient().when(passwordHasher.hash(anyString())).thenReturn("new-hash");
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    // ---------- troca (logado) ----------

    private ChangePassword changePassword() {
        return new ChangePassword(credentialRepository, sessionRepository, customerDirectory, passwordHasher, eventPublisher, clock);
    }

    @Test
    void troca_deveDerrubarAsOutrasSessoes_eAvisar() {
        UUID current = UUID.randomUUID();
        when(credentialRepository.findByCustomerId(customerId)).thenReturn(Optional.of(credential));
        when(passwordHasher.matches("Antiga-123", "old-hash")).thenReturn(true);
        when(sessionRepository.revokeAllByCustomerExcept(customerId, current, "PASSWORD_CHANGED", now())).thenReturn(2);

        int revoked = changePassword().execute(new ChangePassword.Input(customerId, current, "Antiga-123", "Nova-senha-9"));

        assertThat(revoked).isEqualTo(2);
        assertThat(credential.getPasswordHash()).isEqualTo("new-hash");
        verify(eventPublisher).publishPasswordChanged(customerId, AuthEventPublisher.PasswordChangeReason.CHANGED);
    }

    @Test
    void troca_comSenhaAtualErrada_deveContarNaTrava() {
        when(credentialRepository.findByCustomerId(customerId)).thenReturn(Optional.of(credential));
        when(passwordHasher.matches(eq("chute"), any())).thenReturn(false);

        for (int i = 0; i < Credential.MAX_FAILED_ATTEMPTS; i++) {
            assertThatThrownBy(() -> changePassword().execute(new ChangePassword.Input(customerId, UUID.randomUUID(), "chute", "Nova-senha-9")))
                    .isInstanceOf(UnauthorizedException.class);
        }
        // quem roubou a sessão não fica chutando: a mesma trava do login
        assertThatThrownBy(() -> changePassword().execute(new ChangePassword.Input(customerId, UUID.randomUUID(), "chute", "Nova-senha-9")))
                .isInstanceOf(TooManyRequestsException.class);
        verify(sessionRepository, never()).revokeAllByCustomerExcept(any(), any(), any(), any());
    }

    @Test
    void troca_deveRecusarSenhaIgualOuFraca() {
        when(credentialRepository.findByCustomerId(customerId)).thenReturn(Optional.of(credential));
        when(passwordHasher.matches("Antiga-123", "old-hash")).thenReturn(true);

        assertThatThrownBy(() -> changePassword().execute(new ChangePassword.Input(customerId, UUID.randomUUID(), "Antiga-123", "Antiga-123")))
                .isInstanceOf(ValidationException.class).hasMessageContaining("diferente");
        assertThatThrownBy(() -> changePassword().execute(new ChangePassword.Input(customerId, UUID.randomUUID(), "Antiga-123", "curta")))
                .isInstanceOf(ValidationException.class);
        verifyNoInteractions(eventPublisher);
    }

    // ---------- esqueci a senha ----------

    private ForgotPassword forgot() {
        return new ForgotPassword(customerDirectory, resetRepository, sender, clock, true);
    }

    @Test
    void esqueci_deveResponderIgual_paraCpfInexistente() {
        when(customerDirectory.findByCpf("00000000000")).thenReturn(Optional.empty());

        ForgotPassword.Output output = forgot().execute("00000000000");

        assertThat(output.message()).isEqualTo(ForgotPassword.MESSAGE);
        assertThat(output.devResetToken()).isNull();
        verifyNoInteractions(sender, resetRepository);
    }

    @Test
    void esqueci_deveInvalidarLinksAnteriores_eEnviarUmNovo() {
        when(customerDirectory.findByCpf(CPF)).thenReturn(Optional.of(new CustomerDirectory.CustomerIdentity(customerId, false)));

        ForgotPassword.Output output = forgot().execute(CPF);

        assertThat(output.message()).isEqualTo(ForgotPassword.MESSAGE);
        verify(resetRepository).invalidatePending(customerId, now());
        verify(resetRepository).insert(RefreshTokenValue.hash(output.devResetToken()), customerId, now(),
                now().plus(ForgotPassword.TOKEN_TTL));
        verify(sender).send(eq(customerId), eq(output.devResetToken()), any());
    }

    @Test
    void esqueci_naoDeveEnviar_paraClienteBloqueado_nemAlemDoLimite() {
        when(customerDirectory.findByCpf("bloqueado")).thenReturn(Optional.of(new CustomerDirectory.CustomerIdentity(customerId, true)));
        assertThat(forgot().execute("bloqueado").devResetToken()).isNull();

        UUID other = UUID.randomUUID();
        when(customerDirectory.findByCpf(CPF)).thenReturn(Optional.of(new CustomerDirectory.CustomerIdentity(other, false)));
        when(resetRepository.countCreatedSince(other, now().minusHours(1))).thenReturn(ForgotPassword.MAX_REQUESTS_PER_HOUR);
        assertThat(forgot().execute(CPF).devResetToken()).isNull();

        verifyNoInteractions(sender);
    }

    @Test
    void semFlagDeDesenvolvimento_oTokenNuncaVoltaNaResposta() {
        when(customerDirectory.findByCpf(CPF)).thenReturn(Optional.of(new CustomerDirectory.CustomerIdentity(customerId, false)));

        var output = new ForgotPassword(customerDirectory, resetRepository, sender, clock, false).execute(CPF);

        assertThat(output.devResetToken()).isNull();
        verify(sender).send(eq(customerId), anyString(), any());
    }

    // ---------- redefinição ----------

    private ResetPassword reset() {
        return new ResetPassword(resetRepository, credentialRepository, sessionRepository, customerDirectory, passwordHasher,
                eventPublisher, clock);
    }

    private void tokenValid(String token) {
        when(resetRepository.findByHash(RefreshTokenValue.hash(token))).thenReturn(Optional.of(
                new PasswordResetRepository.ResetToken(RefreshTokenValue.hash(token), customerId, now().plusMinutes(10), null)));
    }

    @Test
    void reset_deveTrocarDestravarEDerrubarTodasAsSessoes() {
        for (int i = 0; i < Credential.MAX_FAILED_ATTEMPTS; i++) credential.registerFailedAttempt(now());
        tokenValid("link");
        when(customerDirectory.isBlocked(customerId)).thenReturn(Optional.of(false));
        when(resetRepository.markUsedIfUnused(RefreshTokenValue.hash("link"), now())).thenReturn(true);
        when(credentialRepository.findByCustomerId(customerId)).thenReturn(Optional.of(credential));

        reset().execute(new ResetPassword.Input("link", "Nova-senha-9"));

        assertThat(credential.getPasswordHash()).isEqualTo("new-hash");
        assertThat(credential.isLocked(now())).isFalse();
        verify(sessionRepository).revokeAllByCustomer(customerId, "PASSWORD_RESET", now());
        verify(eventPublisher).publishPasswordChanged(customerId, AuthEventPublisher.PasswordChangeReason.RESET);
    }

    @Test
    void reset_comSenhaFraca_naoQueimaOLink() {
        tokenValid("link");

        assertThatThrownBy(() -> reset().execute(new ResetPassword.Input("link", "123")))
                .isInstanceOf(ValidationException.class);
        verify(resetRepository, never()).markUsedIfUnused(any(), any());
    }

    @Test
    void reset_deveRecusarLinkVencidoOuJaUsado_comAMesmaMensagem() {
        when(resetRepository.findByHash(RefreshTokenValue.hash("vencido"))).thenReturn(Optional.of(
                new PasswordResetRepository.ResetToken("h", customerId, now().minusSeconds(1), null)));
        when(resetRepository.findByHash(RefreshTokenValue.hash("usado"))).thenReturn(Optional.of(
                new PasswordResetRepository.ResetToken("h", customerId, now().plusMinutes(10), now().minusMinutes(1))));

        assertThatThrownBy(() -> reset().execute(new ResetPassword.Input("vencido", "Nova-senha-9")))
                .hasMessage(ResetPassword.INVALID_LINK);
        assertThatThrownBy(() -> reset().execute(new ResetPassword.Input("usado", "Nova-senha-9")))
                .hasMessage(ResetPassword.INVALID_LINK);
        assertThatThrownBy(() -> reset().execute(new ResetPassword.Input("inventado", "Nova-senha-9")))
                .hasMessage(ResetPassword.INVALID_LINK);
        verifyNoInteractions(credentialRepository, sessionRepository);
    }
}
