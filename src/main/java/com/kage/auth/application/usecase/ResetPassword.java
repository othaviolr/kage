package com.kage.auth.application.usecase;

import com.kage.auth.domain.entity.Credential;
import com.kage.auth.domain.repository.CredentialRepository;
import com.kage.auth.domain.repository.PasswordResetRepository;
import com.kage.auth.domain.repository.SessionRepository;
import com.kage.auth.domain.service.AuthEventPublisher;
import com.kage.auth.domain.service.CustomerDirectory;
import com.kage.auth.domain.service.PasswordHasher;
import com.kage.auth.domain.valueobject.PasswordPolicy;
import com.kage.auth.domain.valueobject.RefreshTokenValue;
import com.kage.shared.domain.exception.ValidationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.Clock;

/**
 * Define a senha nova a partir do link de recuperação. Derruba TODAS as sessões: se alguém entrou
 * na conta do cliente, é exatamente esse o momento de expulsá-lo. Também destrava o login.
 *
 * Link inválido, usado ou vencido respondem igual — não interessa a quem tenta adivinhar qual é.
 */
public class ResetPassword {

    static final String INVALID_LINK = "Link de recuperação inválido ou expirado. Peça um novo";

    public record Input(String token, String newPassword) {}

    private final PasswordResetRepository resetRepository;
    private final CredentialRepository credentialRepository;
    private final SessionRepository sessionRepository;
    private final CustomerDirectory customerDirectory;
    private final PasswordHasher passwordHasher;
    private final AuthEventPublisher eventPublisher;
    private final Clock clock;

    public ResetPassword(PasswordResetRepository resetRepository, CredentialRepository credentialRepository,
                         SessionRepository sessionRepository, CustomerDirectory customerDirectory,
                         PasswordHasher passwordHasher, AuthEventPublisher eventPublisher, Clock clock) {
        this.resetRepository = resetRepository;
        this.credentialRepository = credentialRepository;
        this.sessionRepository = sessionRepository;
        this.customerDirectory = customerDirectory;
        this.passwordHasher = passwordHasher;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Transactional
    public void execute(Input input) {
        if (input.token() == null || input.token().isBlank()) throw new ValidationException(INVALID_LINK);
        LocalDateTime now = LocalDateTime.now(clock);
        String hash = RefreshTokenValue.hash(input.token());

        var token = resetRepository.findByHash(hash)
                .filter(t -> t.usedAt() == null && now.isBefore(t.expiresAt()))
                .orElseThrow(() -> new ValidationException(INVALID_LINK));

        // senha fraca: falha ANTES de queimar o link, para o cliente tentar de novo com o mesmo e-mail
        PasswordPolicy.validate(input.newPassword(), customerDirectory.cpfDigitsOf(token.customerId()).orElse(null));
        if (customerDirectory.isBlocked(token.customerId()).orElse(true)) {
            throw new ValidationException(INVALID_LINK);
        }

        if (!resetRepository.markUsedIfUnused(hash, now)) {
            throw new ValidationException(INVALID_LINK); // outro uso simultâneo do mesmo link venceu
        }

        Credential credential = credentialRepository.findByCustomerId(token.customerId())
                .orElseThrow(() -> new ValidationException(INVALID_LINK));
        credential.changePassword(passwordHasher.hash(input.newPassword()), now);
        credentialRepository.update(credential);

        sessionRepository.revokeAllByCustomer(token.customerId(), "PASSWORD_RESET", now);
        eventPublisher.publishPasswordChanged(token.customerId(), AuthEventPublisher.PasswordChangeReason.RESET);
    }
}
