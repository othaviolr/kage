package com.kage.account.domain.service;

import java.util.UUID;

/**
 * Porta do Account para o Savings: a conta tem caixinha ativa? Conta com dinheiro guardado não pode
 * ser encerrada — o saldo das caixinhas ficaria sem destino. A implementação vive em savings.infrastructure.
 */
public interface AccountSavingsCheck {

    boolean hasActiveSavingsBoxes(UUID accountId);
}
