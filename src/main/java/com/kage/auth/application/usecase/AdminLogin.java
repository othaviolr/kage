package com.kage.auth.application.usecase;

import com.kage.auth.domain.entity.Session;
import com.kage.auth.domain.service.PasswordHasher;
import com.kage.shared.domain.exception.TooManyRequestsException;
import com.kage.shared.domain.exception.UnauthorizedException;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Login do backoffice. O operador vem da configuração (kage.security.admin.*): num banco real isso
 * seria SSO corporativo; aqui basta um usuário com papel ADMIN, que também simula os sistemas
 * externos (entrada de dinheiro, bandeira do cartão).
 *
 * Mesma trava do cliente: 5 senhas erradas seguidas bloqueiam por 15 minutos. O contador fica em
 * memória — com várias instâncias, cada uma conta as suas (o limite efetivo vira 5 por instância).
 */
public class AdminLogin {

    static final int MAX_FAILED_ATTEMPTS = 5;
    static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    public record Input(String username, String password) {}

    private final String adminUsername;
    private final String adminPasswordHash;
    private final PasswordHasher passwordHasher;
    private final SessionTokens sessionTokens;
    private final Clock clock;

    private int failedAttempts;
    private LocalDateTime lockedUntil;

    public AdminLogin(String adminUsername, String adminPasswordHash, PasswordHasher passwordHasher,
                      SessionTokens sessionTokens, Clock clock) {
        this.adminUsername = adminUsername;
        this.adminPasswordHash = adminPasswordHash;
        this.passwordHasher = passwordHasher;
        this.sessionTokens = sessionTokens;
        this.clock = clock;
    }

    public AuthTokens execute(Input input) {
        return execute(input, null);
    }

    @Transactional
    public AuthTokens execute(Input input, String userAgent) {
        LocalDateTime now = LocalDateTime.now(clock);
        checkNotLocked(now);

        String username = input.username() == null ? "" : input.username();
        String password = input.password() == null ? "" : input.password();

        // compara usuário em tempo constante e sempre roda o BCrypt, acertando o usuário ou não
        boolean userMatches = MessageDigest.isEqual(username.getBytes(StandardCharsets.UTF_8),
                adminUsername.getBytes(StandardCharsets.UTF_8));
        boolean passwordMatches = passwordHasher.matches(password, adminPasswordHash);

        if (!userMatches || !passwordMatches) {
            registerFailure(now);
            throw new UnauthorizedException("Usuário ou senha inválidos");
        }

        registerSuccess();
        return sessionTokens.open(Session.forAdmin(adminUsername, userAgent, now));
    }

    private synchronized void checkNotLocked(LocalDateTime now) {
        if (lockedUntil != null && now.isBefore(lockedUntil)) {
            long minutes = Math.max(1, Duration.between(now, lockedUntil).toMinutes());
            throw new TooManyRequestsException("Muitas tentativas de login. Tente novamente em " + minutes + " minuto(s)");
        }
    }

    private synchronized void registerFailure(LocalDateTime now) {
        if (lockedUntil != null && !now.isBefore(lockedUntil)) {
            failedAttempts = 0;
            lockedUntil = null;
        }
        failedAttempts++;
        if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
            lockedUntil = now.plus(LOCK_DURATION);
        }
    }

    private synchronized void registerSuccess() {
        failedAttempts = 0;
        lockedUntil = null;
    }
}
