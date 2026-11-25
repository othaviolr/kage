package com.kage.auth.application.usecase;

import com.kage.auth.domain.entity.Session;
import com.kage.auth.domain.repository.SessionRepository;
import com.kage.auth.domain.repository.SessionRepository.RefreshTokenRecord;
import com.kage.auth.domain.service.CustomerDirectory;
import com.kage.auth.domain.service.TokenIssuer;
import com.kage.auth.domain.valueobject.RefreshTokenValue;
import com.kage.shared.domain.exception.UnauthorizedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Abre sessões (login) e gira os tokens delas (refresh).
 *
 * Rotação com detecção de reuso (RFC 6819 / OAuth 2.0 Security BCP): todo refresh devolve um refresh
 * token NOVO e queima o anterior. Se um token já queimado aparecer de novo, alguém tem uma cópia dele
 * — o dono legítimo ou um atacante, não dá para saber qual — então a sessão inteira é encerrada e os
 * dois precisam entrar de novo.
 */
public class SessionTokens {

    private static final Logger logger = LoggerFactory.getLogger(SessionTokens.class);
    static final String INVALID_SESSION = "Sessão inválida ou expirada. Entre novamente";

    private final SessionRepository sessionRepository;
    private final TokenIssuer tokenIssuer;
    private final CustomerDirectory customerDirectory;
    private final Clock clock;

    public SessionTokens(SessionRepository sessionRepository, TokenIssuer tokenIssuer,
                         CustomerDirectory customerDirectory, Clock clock) {
        this.sessionRepository = sessionRepository;
        this.tokenIssuer = tokenIssuer;
        this.customerDirectory = customerDirectory;
        this.clock = clock;
    }

    /** Chamado pelos logins, dentro da transação deles. */
    public AuthTokens open(Session session) {
        sessionRepository.insert(session);
        return issue(session);
    }

    /**
     * noRollbackFor: a revogação por reuso precisa ser gravada mesmo respondendo 401 — senão o
     * atacante seguiria com a sessão.
     */
    @Transactional(noRollbackFor = UnauthorizedException.class)
    public AuthTokens refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) throw new UnauthorizedException(INVALID_SESSION);
        LocalDateTime now = LocalDateTime.now(clock);
        String hash = RefreshTokenValue.hash(refreshToken);

        RefreshTokenRecord record = sessionRepository.findRefreshToken(hash)
                .orElseThrow(() -> new UnauthorizedException(INVALID_SESSION));
        Session session = sessionRepository.findById(record.sessionId())
                .orElseThrow(() -> new UnauthorizedException(INVALID_SESSION));

        // reuso: o token já foi trocado antes (ou outro refresh simultâneo acabou de trocá-lo)
        if (record.usedAt() != null || !sessionRepository.markRefreshTokenUsed(hash, now)) {
            logger.warn("Refresh token reutilizado na sessão {}: possível roubo de token, sessão encerrada", session.getId());
            session.revoke("REFRESH_TOKEN_REUSE", now);
            sessionRepository.update(session);
            throw new UnauthorizedException(INVALID_SESSION);
        }

        if (!session.isActive(now) || !now.isBefore(record.expiresAt())) {
            throw new UnauthorizedException(INVALID_SESSION);
        }

        // defesa extra: o bloqueio já encerra as sessões por evento, mas o refresh confere de novo
        if (session.getRole() == Session.Role.CUSTOMER && customerIsBlocked(session)) {
            session.revoke("CUSTOMER_BLOCKED", now);
            sessionRepository.update(session);
            throw new UnauthorizedException("Acesso bloqueado. Fale com o atendimento");
        }

        session.refreshed(now);
        sessionRepository.update(session);
        return issue(session);
    }

    private boolean customerIsBlocked(Session session) {
        Optional<Boolean> blocked = customerDirectory.isBlocked(session.getCustomerId());
        return blocked.orElse(true); // cliente que não existe mais também não renova
    }

    private AuthTokens issue(Session session) {
        LocalDateTime now = LocalDateTime.now(clock);
        String refreshToken = RefreshTokenValue.generate();
        sessionRepository.insertRefreshToken(RefreshTokenValue.hash(refreshToken), session.getId(), now, session.getExpiresAt());

        TokenIssuer.IssuedToken access = session.getRole() == Session.Role.CUSTOMER
                ? tokenIssuer.issueForCustomer(session.getCustomerId(), session.getId())
                : tokenIssuer.issueForAdmin(session.getSubject(), session.getId());

        return new AuthTokens(access.accessToken(), "Bearer", access.expiresAt(), refreshToken,
                session.getExpiresAt(), session.getId());
    }
}
