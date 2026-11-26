package com.kage.auth.infrastructure.security;

import com.kage.auth.domain.service.LoginCodeSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Envio simulado do código por SMS. Registra QUE um código foi enviado, nunca o código: com ele e a
 * senha, qualquer um que leia o log entra na conta. Para enviar de verdade, declare outro bean
 * LoginCodeSender (Twilio, SNS...).
 */
public class LoggingLoginCodeSender implements LoginCodeSender {

    private static final Logger logger = LoggerFactory.getLogger(LoggingLoginCodeSender.class);

    @Override
    public void send(UUID customerId, String code, LocalDateTime expiresAt) {
        logger.info("[sms] código de verificação de login enviado ao cliente {} (válido até {})", customerId, expiresAt);
    }
}
