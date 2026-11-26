package com.kage.auth.domain.service;

import java.time.LocalDateTime;
import java.util.UUID;

/** Entrega do código de verificação do login (SMS). A implementação de verdade fala com o provedor. */
public interface LoginCodeSender {

    void send(UUID customerId, String code, LocalDateTime expiresAt);
}
