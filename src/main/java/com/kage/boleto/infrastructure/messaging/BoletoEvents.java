package com.kage.boleto.infrastructure.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Eventos publicados no boleto.exchange. */
public final class BoletoEvents {

    private BoletoEvents() {
    }

    public static final String EXCHANGE = "boleto.exchange";
    public static final String BOLETO_PAID = "boleto.paid";
    public static final String PAYMENT_MADE = "boleto.payment.made";

    /** @param via KAGE (outro cliente) ou COMPENSACAO (outro banco) */
    public record BoletoPaidEvent(UUID boletoId, UUID beneficiaryAccountId, String payerName, BigDecimal amount,
                                  BigDecimal paidAmount, String via, Instant occurredAt) {}

    public record PaymentMadeEvent(UUID paymentId, UUID payerAccountId, String kind, String beneficiaryName,
                                   BigDecimal paidAmount, Instant occurredAt) {}
}
