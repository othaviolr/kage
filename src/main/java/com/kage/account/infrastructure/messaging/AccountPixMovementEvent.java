package com.kage.account.infrastructure.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Dinheiro de PIX entrou ou saiu de uma conta (routing keys account.pix.sent/received e
 * account.pix.refund.sent/received). Um evento por conta afetada: quem consome (ex.: notificações)
 * não precisa saber quem era a outra ponta.
 *
 * @param referenceId transação PIX ou estorno que originou o movimento
 */
public record AccountPixMovementEvent(UUID accountId, UUID referenceId, BigDecimal amount, Instant occurredAt) {
}
