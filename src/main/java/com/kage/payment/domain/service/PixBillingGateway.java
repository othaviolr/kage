package com.kage.payment.domain.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta do PIX para as cobranças do próprio Kage que chegam como PIX copia e cola (hoje, o PIX da
 * fatura do cartão). Pagar pela conta Kage é o mesmo que o dinheiro chegar pela liquidação, só que na
 * hora e na mesma transação do débito do pagador.
 */
public interface PixBillingGateway {

    /**
     * @param notPayableReason nulo = pode pagar; senão, por que não (já pago, cancelado, vencido)
     */
    record Billing(UUID chargeId, String beneficiaryName, String description, BigDecimal amount,
                   LocalDateTime expiresAt, String notPayableReason) {}

    /** A chave é a que recebe as cobranças do Kage? */
    boolean receives(String pixKey);

    /** A cobrança com exatamente esse copia e cola. */
    Optional<Billing> find(String code);

    /** O dinheiro chegou: a cobrança fica paga. Roda na transação do pagamento. */
    void settle(UUID chargeId);
}
