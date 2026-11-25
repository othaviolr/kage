package com.kage.auth.domain.service;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Entrega o link de recuperação fora do app (e-mail/SMS cadastrado) — a caixa de entrada não serve,
 * porque exige estar logado. A implementação nunca deve registrar o token em log.
 */
public interface PasswordResetSender {

    void send(UUID customerId, String resetToken, LocalDateTime expiresAt);
}
