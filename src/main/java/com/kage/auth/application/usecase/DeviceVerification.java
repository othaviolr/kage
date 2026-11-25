package com.kage.auth.application.usecase;

import com.kage.auth.domain.entity.Session;
import com.kage.auth.domain.repository.DeviceVerificationRepository;
import com.kage.auth.domain.repository.DeviceVerificationRepository.Challenge;
import com.kage.auth.domain.service.AuthEventPublisher;
import com.kage.auth.domain.service.CustomerDirectory;
import com.kage.auth.domain.service.LoginCodeSender;
import com.kage.auth.domain.valueobject.DeviceId;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.exception.TooManyRequestsException;
import com.kage.shared.domain.exception.UnauthorizedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Verificação em duas etapas no login de dispositivo novo. A senha prova que a pessoa sabe algo; o
 * código por SMS prova que ela tem o celular do cliente. Com os dois, o aparelho passa a ser confiável
 * e os próximos logins dele entram direto.
 *
 * - Código de 6 dígitos, uso único, {@link #CODE_TTL}, até {@link #MAX_ATTEMPTS} tentativas; pedir
 *   outro invalida o anterior; no máximo {@link #MAX_CODES_PER_HOUR} por hora (o SMS custa, e não
 *   pode virar um jeito de bombardear o celular de alguém).
 * - O código vale só para o aparelho que pediu: o desafio guarda o hash do X-Device-Id.
 * - Aparelho novo confiado gera o alerta "novo dispositivo conectado".
 * - O aparelho do cadastro já nasce confiável (SignUp); redefinir a senha esquece todos (ResetPassword).
 */
public class DeviceVerification {

    private static final Logger logger = LoggerFactory.getLogger(DeviceVerification.class);

    static final Duration CODE_TTL = Duration.ofMinutes(5);
    static final int MAX_ATTEMPTS = 5;
    static final int MAX_CODES_PER_HOUR = 5;
    static final String INVALID_CODE = "Código inválido ou expirado. Entre de novo para receber outro";
    static final String MESSAGE = "Dispositivo novo: enviamos um código de 6 dígitos por SMS para o celular cadastrado";

    /** @param devCode só preenchido com kage.security.login-code.expose-code=true (desenvolvimento) */
    public record LoginChallenge(boolean verificationRequired, UUID challengeId, LocalDateTime expiresAt, String message,
                                 String devCode) {}

    public record DeviceView(UUID id, String userAgent, LocalDateTime trustedAt, LocalDateTime lastSeenAt) {}

    private final DeviceVerificationRepository repository;
    private final LoginCodeSender sender;
    private final SessionTokens sessionTokens;
    private final CustomerDirectory customerDirectory;
    private final AuthEventPublisher eventPublisher;
    private final Clock clock;
    private final boolean exposeCodeForDevelopment;

    public DeviceVerification(DeviceVerificationRepository repository, LoginCodeSender sender, SessionTokens sessionTokens,
                              CustomerDirectory customerDirectory, AuthEventPublisher eventPublisher, Clock clock,
                              boolean exposeCodeForDevelopment) {
        this.repository = repository;
        this.sender = sender;
        this.sessionTokens = sessionTokens;
        this.customerDirectory = customerDirectory;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.exposeCodeForDevelopment = exposeCodeForDevelopment;
        if (exposeCodeForDevelopment) {
            logger.warn("kage.security.login-code.expose-code=true: o código do login volta na resposta. NUNCA use em produção");
        }
    }

    /** Chamado pelo login (na transação dele), já com a senha conferida. */
    public boolean isTrusted(UUID customerId, String deviceHash, LocalDateTime now) {
        return deviceHash != null && repository.touchTrusted(customerId, deviceHash, now);
    }

    /** Chamado pelo login (na transação dele): manda o código e devolve o desafio. */
    public LoginChallenge challenge(UUID customerId, String deviceHash, String userAgent, LocalDateTime now) {
        if (repository.countChallengesSince(customerId, now.minusHours(1)) >= MAX_CODES_PER_HOUR) {
            throw new TooManyRequestsException("Muitos códigos pedidos. Tente novamente mais tarde");
        }
        repository.invalidatePendingChallenges(customerId, now);

        UUID id = UUID.randomUUID();
        String code = DeviceId.newLoginCode();
        LocalDateTime expiresAt = now.plus(CODE_TTL);
        repository.insertChallenge(new Challenge(id, customerId, DeviceId.hashCode(id, code), deviceHash, userAgent, now,
                expiresAt, 0, null));
        sender.send(customerId, code, expiresAt);
        return new LoginChallenge(true, id, expiresAt, MESSAGE, exposeCodeForDevelopment ? code : null);
    }

    /**
     * Confere o código e abre a sessão. noRollbackFor: a tentativa errada precisa ficar gravada mesmo
     * respondendo 401 — senão o limite de tentativas nunca chegaria.
     */
    @Transactional(noRollbackFor = UnauthorizedException.class)
    public AuthTokens verify(UUID challengeId, String code, String deviceId, String userAgent) {
        if (challengeId == null || code == null) throw new UnauthorizedException(INVALID_CODE);
        LocalDateTime now = LocalDateTime.now(clock);
        String deviceHash = DeviceId.hashOrNull(deviceId);

        Challenge challenge = repository.findChallenge(challengeId)
                .filter(c -> c.usedAt() == null && now.isBefore(c.expiresAt()) && c.attempts() < MAX_ATTEMPTS)
                .orElseThrow(() -> new UnauthorizedException(INVALID_CODE));
        // o código foi pedido por outro aparelho
        if (challenge.deviceHash() != null && !challenge.deviceHash().equals(deviceHash)) {
            throw new UnauthorizedException(INVALID_CODE);
        }

        if (!constantTimeEquals(DeviceId.hashCode(challengeId, code.trim()), challenge.codeHash())) {
            repository.registerFailedAttempt(challengeId);
            int left = MAX_ATTEMPTS - challenge.attempts() - 1;
            throw new UnauthorizedException(left > 0
                    ? "Código incorreto. Restam " + left + " tentativa(s)"
                    : "Código incorreto. Entre de novo para receber outro");
        }
        if (!repository.markChallengeUsed(challengeId, now)) throw new UnauthorizedException(INVALID_CODE);

        if (customerDirectory.isBlocked(challenge.customerId()).orElse(true)) {
            throw new UnauthorizedException("Acesso bloqueado. Fale com o atendimento");
        }

        if (challenge.deviceHash() != null) {
            repository.trust(challenge.customerId(), challenge.deviceHash(), challenge.userAgent(), now);
            eventPublisher.publishNewDeviceTrusted(challenge.customerId(), challenge.userAgent());
        }
        return sessionTokens.open(Session.forCustomer(challenge.customerId(),
                userAgent != null ? userAgent : challenge.userAgent(), now));
    }

    public List<DeviceView> list(UUID customerId) {
        return repository.listTrusted(customerId).stream()
                .map(d -> new DeviceView(d.id(), d.userAgent(), d.trustedAt(), d.lastSeenAt()))
                .toList();
    }

    /** Esquece um aparelho: o próximo login dele pede código de novo. */
    public void forget(UUID deviceId, UUID customerId) {
        if (!repository.revokeTrusted(deviceId, customerId, LocalDateTime.now(clock))) {
            throw new NotFoundException("Dispositivo não encontrado");
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
