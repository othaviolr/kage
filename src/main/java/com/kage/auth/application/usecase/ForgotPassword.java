package com.kage.auth.application.usecase;

import com.kage.auth.domain.repository.PasswordResetRepository;
import com.kage.auth.domain.service.CustomerDirectory;
import com.kage.auth.domain.service.PasswordResetSender;
import com.kage.auth.domain.valueobject.RefreshTokenValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * "Esqueci minha senha". A resposta é sempre a mesma, exista o CPF ou não: a tela de recuperação não
 * pode virar um jeito de descobrir quem é cliente.
 *
 * - Link de uso único, válido por {@link #TOKEN_TTL}, guardado só como SHA-256.
 * - Pedir de novo invalida o link anterior.
 * - No máximo {@link #MAX_REQUESTS_PER_HOUR} por hora: além disso, ignora em silêncio (evita usar o
 *   Kage para bombardear a caixa de e-mail de alguém).
 * - Cliente bloqueado não recebe link: a recuperação não pode furar o bloqueio.
 */
public class ForgotPassword {

    private static final Logger logger = LoggerFactory.getLogger(ForgotPassword.class);

    static final Duration TOKEN_TTL = Duration.ofMinutes(30);
    static final int MAX_REQUESTS_PER_HOUR = 3;

    /** @param devResetToken só preenchido com kage.security.password-reset.expose-token=true (desenvolvimento) */
    public record Output(String message, String devResetToken) {}

    static final String MESSAGE = "Se o CPF estiver cadastrado, enviamos as instruções de recuperação para o e-mail do cliente.";

    private final CustomerDirectory customerDirectory;
    private final PasswordResetRepository resetRepository;
    private final PasswordResetSender sender;
    private final Clock clock;
    private final boolean exposeTokenForDevelopment;

    public ForgotPassword(CustomerDirectory customerDirectory, PasswordResetRepository resetRepository,
                          PasswordResetSender sender, Clock clock, boolean exposeTokenForDevelopment) {
        this.customerDirectory = customerDirectory;
        this.resetRepository = resetRepository;
        this.sender = sender;
        this.clock = clock;
        this.exposeTokenForDevelopment = exposeTokenForDevelopment;
        if (exposeTokenForDevelopment) {
            logger.warn("kage.security.password-reset.expose-token=true: o token de recuperação volta na resposta. NUNCA use em produção");
        }
    }

    @Transactional
    public Output execute(String cpf) {
        Optional<CustomerDirectory.CustomerIdentity> customer = customerDirectory.findByCpf(cpf);
        if (customer.isEmpty() || customer.get().blocked()) {
            return new Output(MESSAGE, null);
        }

        LocalDateTime now = LocalDateTime.now(clock);
        var customerId = customer.get().customerId();
        if (resetRepository.countCreatedSince(customerId, now.minusHours(1)) >= MAX_REQUESTS_PER_HOUR) {
            logger.warn("Limite de pedidos de recuperação atingido para o cliente {}", customerId);
            return new Output(MESSAGE, null);
        }

        resetRepository.invalidatePending(customerId, now);
        String token = RefreshTokenValue.generate();
        LocalDateTime expiresAt = now.plus(TOKEN_TTL);
        resetRepository.insert(RefreshTokenValue.hash(token), customerId, now, expiresAt);
        sender.send(customerId, token, expiresAt);

        return new Output(MESSAGE, exposeTokenForDevelopment ? token : null);
    }
}
