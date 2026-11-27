package com.kage.boleto.domain.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta do Boleto para as cobranças do próprio Kage que não são boletos de cliente: hoje, o boleto da
 * fatura do cartão. Pago pelo "pagar boleto" da conta Kage, o dinheiro não vai para uma conta
 * beneficiária: vai direto para a fatura, na hora (sem esperar a compensação).
 */
public interface BillingBoletoGateway {

    /**
     * @param notPayableReason por que não dá para pagar (pago, cancelado, vencido); nulo = pagável
     */
    record Billing(UUID chargeId, String beneficiaryName, String description, BigDecimal amount, LocalDate dueDate,
                   String notPayableReason) {}

    /** Vazio = o código não é de uma cobrança do Kage (segue como boleto de cliente). */
    Optional<Billing> find(String barcode);

    /** O dinheiro chegou: paga a fatura. Chamar na transação do débito do pagador. */
    void settle(UUID chargeId);
}
