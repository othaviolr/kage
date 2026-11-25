package com.kage.auth.application.usecase;

import com.kage.auth.domain.entity.Session;
import com.kage.auth.domain.repository.SessionRepository;
import com.kage.auth.domain.service.CustomerDirectory;
import com.kage.auth.domain.service.TokenIssuer;
import com.kage.shared.domain.exception.UnauthorizedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Rotação de refresh token com detecção de reuso, contra um repositório em memória (o que importa
 * aqui é a sequência de estados: token usado, sessão revogada).
 */
class SessionTokensTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-25T13:00:00Z"));
    private final InMemorySessions sessions = new InMemorySessions();
    private final Map<UUID, Boolean> blocked = new HashMap<>();
    private SessionTokens tokens;
    private UUID customerId;

    @BeforeEach
    void setUp() {
        TokenIssuer issuer = new TokenIssuer() {
            public IssuedToken issueForCustomer(UUID c, UUID s) { return new IssuedToken("access-" + s, clock.instant()); }
            public IssuedToken issueForAdmin(String u, UUID s) { return new IssuedToken("admin-" + s, clock.instant()); }
        };
        CustomerDirectory directory = new CustomerDirectory() {
            public RegisteredCustomer register(NewCustomer c) { throw new UnsupportedOperationException(); }
            public Optional<CustomerIdentity> findByCpf(String cpf) { return Optional.empty(); }
            public Optional<Boolean> isBlocked(UUID id) { return Optional.ofNullable(blocked.get(id)); }
            public Optional<String> cpfDigitsOf(UUID id) { return Optional.empty(); }
        };
        tokens = new SessionTokens(sessions, issuer, directory, clock);
        customerId = UUID.randomUUID();
        blocked.put(customerId, false);
    }

    private AuthTokens login() {
        return tokens.open(Session.forCustomer(customerId, "JUnit", LocalDateTime.now(clock)));
    }

    @Test
    void refresh_deveGirarOToken_eManterAMesmaSessao() {
        AuthTokens first = login();

        AuthTokens second = tokens.refresh(first.refreshToken());

        assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
        assertThat(second.sessionId()).isEqualTo(first.sessionId());
        assertThat(sessions.isActive(first.sessionId(), LocalDateTime.now(clock))).isTrue();
    }

    @Test
    void reusoDeTokenJaTrocado_deveDerrubarASessaoInteira() {
        AuthTokens first = login();
        AuthTokens second = tokens.refresh(first.refreshToken()); // dono legítimo renovou

        // alguém com a cópia antiga tenta usar
        assertThatThrownBy(() -> tokens.refresh(first.refreshToken())).isInstanceOf(UnauthorizedException.class);

        assertThat(sessions.isActive(first.sessionId(), LocalDateTime.now(clock))).isFalse();
        assertThat(sessions.findById(first.sessionId()).orElseThrow().getRevokeReason()).isEqualTo("REFRESH_TOKEN_REUSE");
        // e o token novo também morre: não dá para saber quem é o dono legítimo
        assertThatThrownBy(() -> tokens.refresh(second.refreshToken())).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void refresh_deveRecusar_tokenDesconhecido() {
        assertThatThrownBy(() -> tokens.refresh("inventado")).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> tokens.refresh(null)).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void refresh_deveRecusar_depoisDe30DiasSemUso() {
        AuthTokens first = login();
        clock.advance(Session.IDLE_TIMEOUT.plusMinutes(1));

        assertThatThrownBy(() -> tokens.refresh(first.refreshToken())).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void refresh_deveEstenderAValidade_quemUsaNaoEDeslogado() {
        AuthTokens first = login();
        clock.advance(Duration.ofDays(20));
        AuthTokens second = tokens.refresh(first.refreshToken());
        clock.advance(Duration.ofDays(20)); // 40 dias desde o login, 20 desde o último uso

        assertThat(tokens.refresh(second.refreshToken()).sessionId()).isEqualTo(first.sessionId());
    }

    @Test
    void refresh_deveEncerrarASessao_quandoClienteFoiBloqueado() {
        AuthTokens first = login();
        blocked.put(customerId, true);

        assertThatThrownBy(() -> tokens.refresh(first.refreshToken()))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("bloqueado");
        assertThat(sessions.findById(first.sessionId()).orElseThrow().getRevokeReason()).isEqualTo("CUSTOMER_BLOCKED");
    }

    @Test
    void refresh_deveRecusar_sessaoEncerradaPorLogout() {
        AuthTokens first = login();
        new ManageSessions(sessions, clock).logout(first.sessionId());

        assertThatThrownBy(() -> tokens.refresh(first.refreshToken())).isInstanceOf(UnauthorizedException.class);
    }

    // ---------- fakes ----------

    static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now) { this.now = now; }
        void advance(Duration d) { now = now.plus(d); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    static final class InMemorySessions implements SessionRepository {
        private final Map<UUID, Session> sessions = new HashMap<>();
        private final Map<String, RefreshTokenRecord> refreshTokens = new HashMap<>();

        public void insert(Session s) { sessions.put(s.getId(), s); }
        public void update(Session s) { sessions.put(s.getId(), s); }
        public Optional<Session> findById(UUID id) { return Optional.ofNullable(sessions.get(id)); }
        public boolean isActive(UUID id, LocalDateTime now) { return findById(id).map(s -> s.isActive(now)).orElse(false); }
        public List<Session> findActiveByCustomer(UUID c, LocalDateTime now) {
            return sessions.values().stream().filter(s -> c.equals(s.getCustomerId()) && s.isActive(now)).toList();
        }
        public int revokeAllByCustomer(UUID c, String reason, LocalDateTime now) {
            List<Session> active = findActiveByCustomer(c, now);
            active.forEach(s -> s.revoke(reason, now));
            return active.size();
        }
        public int revokeAllByCustomerExcept(UUID c, UUID keep, String reason, LocalDateTime now) {
            List<Session> others = findActiveByCustomer(c, now).stream().filter(s -> !s.getId().equals(keep)).toList();
            others.forEach(s -> s.revoke(reason, now));
            return others.size();
        }
        public void insertRefreshToken(String hash, UUID sessionId, LocalDateTime issuedAt, LocalDateTime expiresAt) {
            refreshTokens.put(hash, new RefreshTokenRecord(hash, sessionId, expiresAt, null));
        }
        public Optional<RefreshTokenRecord> findRefreshToken(String hash) { return Optional.ofNullable(refreshTokens.get(hash)); }
        public boolean markRefreshTokenUsed(String hash, LocalDateTime now) {
            RefreshTokenRecord r = refreshTokens.get(hash);
            if (r == null || r.usedAt() != null) return false;
            refreshTokens.put(hash, new RefreshTokenRecord(hash, r.sessionId(), r.expiresAt(), now));
            return true;
        }
    }
}
