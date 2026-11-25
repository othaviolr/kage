package com.kage.auth.infrastructure.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.kage.auth.application.usecase.ManageSessions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

import java.util.UUID;

/**
 * Cliente bloqueado pelo backoffice: todas as sessões dele caem. Idempotente por natureza —
 * reprocessar só tenta revogar o que já está revogado.
 */
public class AuthCustomerEventConsumer {

    private static final Logger logger = LoggerFactory.getLogger(AuthCustomerEventConsumer.class);

    private final ManageSessions manageSessions;

    public AuthCustomerEventConsumer(ManageSessions manageSessions) {
        this.manageSessions = manageSessions;
    }

    @RabbitListener(queues = AuthRabbitMQConfig.CUSTOMER_BLOCKED_QUEUE)
    public void onCustomerBlocked(CustomerBlocked event) {
        int revoked = manageSessions.revokeAllBecauseBlocked(event.customerId());
        logger.info("Cliente {} bloqueado: {} sessão(ões) encerrada(s)", event.customerId(), revoked);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CustomerBlocked(UUID customerId) {}
}
