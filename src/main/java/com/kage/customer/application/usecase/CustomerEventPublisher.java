package com.kage.customer.application.usecase;

import java.util.UUID;

/** Porta de saída dos eventos do cliente. A implementação grava no outbox: chamar dentro da transação. */
public interface CustomerEventPublisher {

    void publishCustomerBlocked(UUID customerId);
}
