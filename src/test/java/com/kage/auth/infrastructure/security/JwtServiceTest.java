package com.kage.auth.infrastructure.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "test-secret-with-at-least-32-bytes-1234567890";
    private static final UUID SESSION = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-25T13:00:00Z");

    private JwtService at(Instant instant) {
        return new JwtService(SECRET, Duration.ofMinutes(15), Clock.fixed(instant, ZoneOffset.UTC));
    }

    @Test
    void tokenDeCliente_deveVoltarComIdEPapel() {
        UUID customerId = UUID.randomUUID();
        String token = at(NOW).issueForCustomer(customerId, SESSION).accessToken();

        AuthenticatedUser user = at(NOW).parse(token).orElseThrow();

        assertThat(user.customerId()).isEqualTo(customerId);
        assertThat(user.role()).isEqualTo(AuthenticatedUser.Role.CUSTOMER);
        assertThat(user.isAdmin()).isFalse();
        assertThat(user.sessionId()).isEqualTo(SESSION);
    }

    @Test
    void tokenDeAdmin_naoTemCustomerId() {
        String token = at(NOW).issueForAdmin("admin", SESSION).accessToken();

        AuthenticatedUser user = at(NOW).parse(token).orElseThrow();

        assertThat(user.isAdmin()).isTrue();
        assertThat(user.customerId()).isNull();
        assertThat(user.username()).isEqualTo("admin");
    }

    @Test
    void tokenExpirado_deveSerRecusado() {
        String token = at(NOW).issueForCustomer(UUID.randomUUID(), SESSION).accessToken();

        assertThat(at(NOW.plus(Duration.ofMinutes(16))).parse(token)).isEmpty();
    }

    @Test
    void tokenAdulterado_deveSerRecusado() {
        String token = at(NOW).issueForCustomer(UUID.randomUUID(), SESSION).accessToken();
        String[] parts = token.split("\\.");
        // troca o payload por outro (papel ADMIN) mantendo a assinatura original
        String forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"iss\":\"kage\",\"sub\":\"admin\",\"role\":\"ADMIN\"}".getBytes(StandardCharsets.UTF_8));

        assertThat(at(NOW).parse(parts[0] + "." + forgedPayload + "." + parts[2])).isEmpty();
    }

    @Test
    void tokenAssinadoComOutraChave_deveSerRecusado() {
        String foreign = Jwts.builder().issuer("kage").subject("admin").claim("role", "ADMIN")
                .expiration(Date.from(NOW.plusSeconds(600)))
                .signWith(Keys.hmacShaKeyFor("another-secret-with-at-least-32-bytes-xyz".getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThat(at(NOW).parse(foreign)).isEmpty();
    }

    @Test
    void tokenSemSessao_deveSerRecusado() {
        // tokens emitidos antes das sessões existirem: assinatura válida, mas não há como revogá-los
        String legacy = Jwts.builder().issuer("kage").subject(UUID.randomUUID().toString()).claim("role", "CUSTOMER")
                .expiration(Date.from(NOW.plusSeconds(600)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThat(at(NOW).parse(legacy)).isEmpty();
    }

    @Test
    void lixo_deveSerRecusadoSemExcecao() {
        assertThat(at(NOW).parse("nao-e-um-jwt")).isEmpty();
    }

    @Test
    void segredoCurto_deveImpedirASubida() {
        assertThatThrownBy(() -> new JwtService("curto", Duration.ofMinutes(15), Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class);
    }
}
