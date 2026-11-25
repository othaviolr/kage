package com.kage.auth.domain.service;

import java.util.UUID;

/** Eventos do auth (ex.: para o alerta "sua senha foi alterada"). Grava no outbox: chamar na transação. */
public interface AuthEventPublisher {

    enum PasswordChangeReason { CHANGED, RESET }

    void publishPasswordChanged(UUID customerId, PasswordChangeReason reason);

    /** Um aparelho novo passou pela verificação e virou confiável (alerta "novo dispositivo conectado"). */
    void publishNewDeviceTrusted(UUID customerId, String userAgent);
}
