package com.kage.auth.infrastructure.security;

import com.kage.auth.domain.service.TokenIssuer;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * Emite e valida JWT HS256. Token curto (padrão 15 min): como não há revogação, é o tempo máximo que
 * um token vazado — ou de um cliente que acabou de ser bloqueado — continua valendo.
 */
public class JwtService implements TokenIssuer {

    static final String ISSUER = "kage";
    static final String ROLE_CLAIM = "role";

    private final SecretKey key;
    private final Duration ttl;
    private final Clock clock;

    public JwtService(String secret, Duration ttl, Clock clock) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("kage.security.jwt.secret precisa de pelo menos 32 bytes (256 bits) para HS256");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.ttl = ttl;
        this.clock = clock;
    }

    @Override
    public IssuedToken issueForCustomer(UUID customerId) {
        return issue(customerId.toString(), AuthenticatedUser.Role.CUSTOMER);
    }

    @Override
    public IssuedToken issueForAdmin(String username) {
        return issue(username, AuthenticatedUser.Role.ADMIN);
    }

    private IssuedToken issue(String subject, AuthenticatedUser.Role role) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(ttl);
        String token = Jwts.builder()
                .issuer(ISSUER)
                .subject(subject)
                .id(UUID.randomUUID().toString())
                .claim(ROLE_CLAIM, role.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
        return new IssuedToken(token, expiresAt);
    }

    /** Token válido (assinatura, emissor, expiração) vira o usuário; qualquer problema vira vazio. */
    public Optional<AuthenticatedUser> parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(ISSUER)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            AuthenticatedUser.Role role = AuthenticatedUser.Role.valueOf(claims.get(ROLE_CLAIM, String.class));
            UUID customerId = role == AuthenticatedUser.Role.CUSTOMER ? UUID.fromString(claims.getSubject()) : null;
            return Optional.of(new AuthenticatedUser(customerId, claims.getSubject(), role));
        } catch (JwtException | IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
    }
}
