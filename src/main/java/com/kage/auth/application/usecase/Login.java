package com.kage.auth.application.usecase;

import com.kage.auth.domain.entity.Credential;
import com.kage.auth.domain.entity.Session;
import com.kage.auth.domain.repository.CredentialRepository;
import com.kage.auth.domain.service.CustomerDirectory;
import com.kage.auth.domain.service.PasswordHasher;
import com.kage.auth.domain.valueobject.DeviceId;
import com.kage.shared.domain.exception.TooManyRequestsException;
import com.kage.shared.domain.exception.UnauthorizedException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Login do cliente por CPF + senha.
 *
 * - Mensagem única para CPF inexistente e senha errada, e o hash é comparado mesmo quando o CPF não
 *   existe: nem a resposta nem o tempo dela revelam quem é cliente.
 * - A tentativa errada é gravada mesmo com a exceção (noRollbackFor), senão o contador de força
 *   bruta nunca subiria.
 * - Dispositivo novo: a senha certa não basta, vem o código por SMS (DeviceVerification).
 */
public class Login {

    static final String INVALID_CREDENTIALS = "CPF ou senha inválidos";

    public record Input(String cpf, String password) {}

    private final CustomerDirectory customerDirectory;
    private final CredentialRepository credentialRepository;
    private final PasswordHasher passwordHasher;
    private final SessionTokens sessionTokens;
    private final DeviceVerification deviceVerification;
    private final Clock clock;
    private final String timingDummyHash;

    public Login(CustomerDirectory customerDirectory, CredentialRepository credentialRepository,
                 PasswordHasher passwordHasher, SessionTokens sessionTokens, DeviceVerification deviceVerification,
                 Clock clock) {
        this.deviceVerification = deviceVerification;
        this.customerDirectory = customerDirectory;
        this.credentialRepository = credentialRepository;
        this.passwordHasher = passwordHasher;
        this.sessionTokens = sessionTokens;
        this.clock = clock;
        this.timingDummyHash = passwordHasher.hash("kage-timing-dummy-password-1");
    }

    /**
     * @param userAgent dispositivo que está entrando, para a lista de sessões ("onde estou logado")
     * @param deviceId  X-Device-Id do app: aparelho confiável entra direto; novo (ou sem id) recebe o
     *                  desafio do código por SMS
     */
    @Transactional(noRollbackFor = UnauthorizedException.class)
    public LoginResult execute(Input input, String userAgent, String deviceId) {
        if (input.cpf() == null || input.cpf().isBlank() || input.password() == null) {
            throw new UnauthorizedException(INVALID_CREDENTIALS);
        }
        String deviceHash = DeviceId.hashOrNull(deviceId);
        LocalDateTime now = LocalDateTime.now(clock);

        Optional<CustomerDirectory.CustomerIdentity> customer = customerDirectory.findByCpf(input.cpf());
        Optional<Credential> credential = customer.flatMap(c -> credentialRepository.findByCustomerId(c.customerId()));

        if (credential.isEmpty()) {
            passwordHasher.matches(input.password(), timingDummyHash);
            throw new UnauthorizedException(INVALID_CREDENTIALS);
        }

        Credential found = credential.get();
        if (found.isLocked(now)) {
            long minutes = Math.max(1, Duration.between(now, found.getLockedUntil()).toMinutes());
            throw new TooManyRequestsException("Muitas tentativas de login. Tente novamente em " + minutes + " minuto(s)");
        }

        if (!passwordHasher.matches(input.password(), found.getPasswordHash())) {
            found.registerFailedAttempt(now);
            credentialRepository.update(found);
            throw new UnauthorizedException(INVALID_CREDENTIALS);
        }

        found.registerSuccessfulLogin(now);
        credentialRepository.update(found);

        // só depois de a senha estar certa: não confirma para estranhos que o CPF é de um cliente bloqueado
        if (customer.get().blocked()) {
            throw new UnauthorizedException("Acesso bloqueado. Fale com o atendimento");
        }

        // senha certa: aparelho confiável entra; novo precisa do código (a senha sozinha não basta)
        if (deviceVerification.isTrusted(found.getCustomerId(), deviceHash, now)) {
            return LoginResult.signedIn(sessionTokens.open(Session.forCustomer(found.getCustomerId(), userAgent, now)));
        }
        return LoginResult.verificationRequired(deviceVerification.challenge(found.getCustomerId(), deviceHash, userAgent, now));
    }
}
