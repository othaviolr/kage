package com.kage.auth.application.usecase;

import com.kage.auth.domain.service.PasswordHasher;
import com.kage.shared.domain.exception.TooManyRequestsException;
import com.kage.shared.domain.exception.UnauthorizedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class AdminLoginTest {

    @Mock PasswordHasher passwordHasher;
    @Mock SessionTokens sessionTokens;

    private final SessionTokensTest.MutableClock clock = new SessionTokensTest.MutableClock(Instant.parse("2026-11-25T13:00:00Z"));
    private AdminLogin adminLogin;

    @BeforeEach
    void setUp() {
        adminLogin = new AdminLogin("admin", "admin-hash", passwordHasher, sessionTokens, clock);
        lenient().when(passwordHasher.matches(anyString(), eq("admin-hash"))).thenReturn(false);
        lenient().when(passwordHasher.matches(eq("certa"), eq("admin-hash"))).thenReturn(true);
        lenient().when(sessionTokens.open(any())).thenReturn(
                new AuthTokens("jwt", "Bearer", Instant.EPOCH, "refresh", LocalDateTime.now(), UUID.randomUUID()));
    }

    private void wrong() {
        assertThatThrownBy(() -> adminLogin.execute(new AdminLogin.Input("admin", "chute")))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void deveTravarDepoisDeCincoErros_mesmoComASenhaCerta() {
        for (int i = 0; i < AdminLogin.MAX_FAILED_ATTEMPTS; i++) wrong();

        assertThatThrownBy(() -> adminLogin.execute(new AdminLogin.Input("admin", "certa")))
                .isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    void travaDeveExpirar_eAcertoZeraAContagem() {
        for (int i = 0; i < AdminLogin.MAX_FAILED_ATTEMPTS; i++) wrong();
        clock.advance(AdminLogin.LOCK_DURATION);

        assertThat(adminLogin.execute(new AdminLogin.Input("admin", "certa")).accessToken()).isEqualTo("jwt");

        // depois do acerto, 4 erros não travam
        for (int i = 0; i < AdminLogin.MAX_FAILED_ATTEMPTS - 1; i++) wrong();
        assertThat(adminLogin.execute(new AdminLogin.Input("admin", "certa")).accessToken()).isEqualTo("jwt");
    }

    @Test
    void usuarioErrado_contaComoTentativaErrada() {
        for (int i = 0; i < AdminLogin.MAX_FAILED_ATTEMPTS; i++) {
            assertThatThrownBy(() -> adminLogin.execute(new AdminLogin.Input("root", "certa")))
                    .isInstanceOf(UnauthorizedException.class);
        }

        assertThatThrownBy(() -> adminLogin.execute(new AdminLogin.Input("admin", "certa")))
                .isInstanceOf(TooManyRequestsException.class);
    }
}
