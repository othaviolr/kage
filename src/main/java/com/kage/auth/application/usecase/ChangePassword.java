package com.kage.auth.application.usecase;

import com.kage.auth.domain.entity.Credential;
import com.kage.auth.domain.repository.CredentialRepository;
import com.kage.auth.domain.repository.SessionRepository;
import com.kage.auth.domain.service.AuthEventPublisher;
import com.kage.auth.domain.service.CustomerDirectory;
import com.kage.auth.domain.service.PasswordHasher;
import com.kage.auth.domain.valueobject.PasswordPolicy;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.exception.TooManyRequestsException;
import com.kage.shared.domain.exception.UnauthorizedException;
import com.kage.shared.domain.exception.ValidationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Troca de senha com o cliente logado.
 *
 * - Exige a senha atual, e o erro conta na mesma trava de força bruta do login: quem roubou uma
 *   sessão não consegue ficar chutando a senha por aqui.
 * - Derruba as outras sessões (mantém a atual): se a troca foi por desconfiança, quem estava com
 *   acesso perde na hora.
 */
public class ChangePassword {

    public record Input(UUID customerId, UUID currentSessionId, String currentPassword, String newPassword) {}

    private final CredentialRepository credentialRepository;
    private final SessionRepository sessionRepository;
    private final CustomerDirectory customerDirectory;
    private final PasswordHasher passwordHasher;
    private final AuthEventPublisher eventPublisher;
    private final Clock clock;

    public ChangePassword(CredentialRepository credentialRepository, SessionRepository sessionRepository,
                          CustomerDirectory customerDirectory, PasswordHasher passwordHasher,
                          AuthEventPublisher eventPublisher, Clock clock) {
        this.credentialRepository = credentialRepository;
        this.sessionRepository = sessionRepository;
        this.customerDirectory = customerDirectory;
        this.passwordHasher = passwordHasher;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    /** @return quantas outras sessões foram encerradas */
    @Transactional(noRollbackFor = UnauthorizedException.class)
    public int execute(Input input) {
        LocalDateTime now = LocalDateTime.now(clock);
        Credential credential = credentialRepository.findByCustomerId(input.customerId())
                .orElseThrow(() -> new NotFoundException("Credencial não encontrada"));

        if (credential.isLocked(now)) {
            throw new TooManyRequestsException("Muitas tentativas com senha errada. Tente novamente mais tarde");
        }
        if (input.currentPassword() == null || !passwordHasher.matches(input.currentPassword(), credential.getPasswordHash())) {
            credential.registerFailedAttempt(now);
            credentialRepository.update(credential);
            throw new UnauthorizedException("Senha atual incorreta");
        }
        if (input.currentPassword().equals(input.newPassword())) {
            throw new ValidationException("A nova senha precisa ser diferente da atual");
        }
        PasswordPolicy.validate(input.newPassword(), customerDirectory.cpfDigitsOf(input.customerId()).orElse(null));

        credential.changePassword(passwordHasher.hash(input.newPassword()), now);
        credentialRepository.update(credential);

        int revoked = sessionRepository.revokeAllByCustomerExcept(input.customerId(), input.currentSessionId(),
                "PASSWORD_CHANGED", now);
        eventPublisher.publishPasswordChanged(input.customerId(), AuthEventPublisher.PasswordChangeReason.CHANGED);
        return revoked;
    }
}
