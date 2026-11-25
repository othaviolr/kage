package com.kage.auth.infrastructure.security;

import com.kage.auth.domain.service.PasswordResetSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Envio simulado do link de recuperação. Registra QUE um link foi enviado, nunca o token: log é lido
 * por muita gente e guardado por muito tempo — token em log é conta invadida. Para enviar de verdade,
 * declare outro bean PasswordResetSender (SES, SendGrid...).
 */
public class LoggingPasswordResetSender implements PasswordResetSender {

    private static final Logger logger = LoggerFactory.getLogger(LoggingPasswordResetSender.class);

    @Override
    public void send(UUID customerId, String resetToken, LocalDateTime expiresAt) {
        logger.info("[e-mail] link de recuperação de senha enviado ao cliente {} (válido até {})", customerId, expiresAt);
    }
}
