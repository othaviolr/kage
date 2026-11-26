package com.kage.auth.application.usecase;

import com.kage.auth.domain.entity.Credential;
import com.kage.auth.domain.repository.CredentialRepository;
import com.kage.auth.domain.service.CustomerDirectory;
import com.kage.auth.domain.service.PasswordHasher;
import com.kage.auth.domain.valueobject.DeviceId;

import com.kage.shared.domain.exception.TooManyRequestsException;
import com.kage.shared.domain.exception.UnauthorizedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoginTest {

    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 25, 10, 0);
    private static final String CPF = "529.982.247-25";

    @Mock CustomerDirectory customerDirectory;
    @Mock CredentialRepository credentialRepository;
    @Mock PasswordHasher passwordHasher;
    @Mock SessionTokens sessionTokens;
    @Mock DeviceVerification deviceVerification;

    private static final String DEVICE = "aparelho-do-cliente-123";

    Login login;
    UUID customerId;
    Credential credential;

    @BeforeEach
    void setUp() {
        when(passwordHasher.hash(anyString())).thenReturn("dummy-hash");
        login = new Login(customerDirectory, credentialRepository, passwordHasher, sessionTokens, deviceVerification,
                Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE));
        customerId = UUID.randomUUID();
        credential = Credential.create(customerId, "real-hash", NOW.minusDays(1));
    }

    private void customerExists(boolean blocked) {
        when(customerDirectory.findByCpf(CPF)).thenReturn(Optional.of(new CustomerDirectory.CustomerIdentity(customerId, blocked)));
        when(credentialRepository.findByCustomerId(customerId)).thenReturn(Optional.of(credential));
    }

    private LoginResult login(String password) {
        return login.execute(new Login.Input(CPF, password), "app", DEVICE);
    }

    @Test
    void execute_deveEmitirToken_quandoSenhaCorreta_eAparelhoConfiavel() {
        customerExists(false);
        when(passwordHasher.matches("Senha-forte-1", "real-hash")).thenReturn(true);
        when(deviceVerification.isTrusted(eq(customerId), eq(DeviceId.hashOrNull(DEVICE)), any())).thenReturn(true);
        when(sessionTokens.open(any())).thenReturn(new AuthTokens("jwt", "Bearer", Instant.EPOCH, "refresh", NOW.plusDays(30), UUID.randomUUID()));

        LoginResult result = login("Senha-forte-1");

        assertThat(result.isVerificationRequired()).isFalse();
        AuthTokens output = result.tokens();
        assertThat(output.accessToken()).isEqualTo("jwt");
        assertThat(output.tokenType()).isEqualTo("Bearer");
        assertThat(output.refreshToken()).isEqualTo("refresh");
        verify(sessionTokens).open(org.mockito.ArgumentMatchers.argThat(s ->
                customerId.equals(s.getCustomerId()) && s.getRole() == com.kage.auth.domain.entity.Session.Role.CUSTOMER));
    }

    @Test
    void execute_devePedirCodigo_semAbrirSessao_quandoAparelhoNovo() {
        customerExists(false);
        when(passwordHasher.matches("Senha-forte-1", "real-hash")).thenReturn(true);
        when(deviceVerification.isTrusted(eq(customerId), any(), any())).thenReturn(false);
        var challenge = new DeviceVerification.LoginChallenge(true, UUID.randomUUID(), NOW.plusMinutes(5), "código enviado", null);
        when(deviceVerification.challenge(eq(customerId), eq(DeviceId.hashOrNull(DEVICE)), eq("app"), any())).thenReturn(challenge);

        LoginResult result = login("Senha-forte-1");

        assertThat(result.isVerificationRequired()).isTrue();
        assertThat(result.challenge()).isEqualTo(challenge);
        verify(sessionTokens, never()).open(any()); // a senha sozinha não abre sessão
    }

    @Test
    void execute_naoDeveRevelarSeOAparelhoEConfiavel_quandoSenhaErrada() {
        customerExists(false);
        when(passwordHasher.matches("errada", "real-hash")).thenReturn(false);

        assertThatThrownBy(() -> login("errada")).isInstanceOf(UnauthorizedException.class);
        verify(deviceVerification, never()).isTrusted(any(), any(), any());
        verify(deviceVerification, never()).challenge(any(), any(), any(), any()); // sem SMS para quem errou a senha
    }

    @Test
    void execute_deveContarTentativaErrada_eResponderMensagemGenerica() {
        customerExists(false);
        when(passwordHasher.matches("errada", "real-hash")).thenReturn(false);

        assertThatThrownBy(() -> login("errada"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage(Login.INVALID_CREDENTIALS);
        assertThat(credential.getFailedAttempts()).isEqualTo(1);
        verify(credentialRepository).update(credential);
    }

    @Test
    void execute_deveResponderAMesmaMensagemEAindaRodarOHash_quandoCpfNaoExiste() {
        when(customerDirectory.findByCpf(CPF)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> login("qualquer1"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage(Login.INVALID_CREDENTIALS);
        // o hash roda mesmo assim: o tempo de resposta não denuncia que o CPF não é cliente
        verify(passwordHasher).matches("qualquer1", "dummy-hash");
    }

    @Test
    void execute_deveRecusarSemConferirSenha_quandoTravado() {
        for (int i = 0; i < Credential.MAX_FAILED_ATTEMPTS; i++) credential.registerFailedAttempt(NOW.minusMinutes(1));
        customerExists(false);

        assertThatThrownBy(() -> login("Senha-forte-1"))
                .isInstanceOf(TooManyRequestsException.class)
                .hasMessageContaining("14 minuto");
        verify(passwordHasher, never()).matches(eq("Senha-forte-1"), any());
    }

    @Test
    void execute_deveRecusar_quandoClienteBloqueado_mesmoComSenhaCerta() {
        customerExists(true);
        when(passwordHasher.matches("Senha-forte-1", "real-hash")).thenReturn(true);

        assertThatThrownBy(() -> login("Senha-forte-1"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("bloqueado");
        verify(sessionTokens, never()).open(any());
    }
}
