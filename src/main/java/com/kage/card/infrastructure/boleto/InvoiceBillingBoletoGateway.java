package com.kage.card.infrastructure.boleto;

import com.kage.boleto.domain.service.BillingBoletoGateway;
import com.kage.card.application.usecase.InvoiceCharges;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;

/**
 * O boleto da fatura do cartão visto pelo "pagar boleto" da conta Kage. Pagar aqui é o mesmo que o
 * dinheiro chegar pela compensação ({@link InvoiceCharges#settle}), só que na hora.
 */
public class InvoiceBillingBoletoGateway implements BillingBoletoGateway {

    static final String BENEFICIARY = "Kage (fatura do cartão)";
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MM/yyyy");

    private final InvoiceCharges invoiceCharges;

    public InvoiceBillingBoletoGateway(InvoiceCharges invoiceCharges) {
        this.invoiceCharges = invoiceCharges;
    }

    @Override
    public Optional<Billing> find(String barcode) {
        return invoiceCharges.findBoleto(barcode).map(c -> new Billing(c.id(), BENEFICIARY,
                "Fatura do cartão Kage de " + YearMonth.parse(c.referenceMonth()).format(MONTH), c.amount(),
                c.expiresAt().toLocalDate(), reason(c.status())));
    }

    @Override
    public void settle(UUID chargeId) {
        invoiceCharges.settle(chargeId, null);
    }

    private static String reason(String status) {
        return switch (status) {
            case "PAID" -> "Boleto já foi pago";
            case "CANCELLED" -> "Boleto cancelado: um boleto novo da fatura substituiu este";
            case "EXPIRED" -> "Boleto vencido: gere outro na fatura";
            default -> null;
        };
    }
}
