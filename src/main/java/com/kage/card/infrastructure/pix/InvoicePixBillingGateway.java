package com.kage.card.infrastructure.pix;

import com.kage.card.application.usecase.InvoiceCharges;
import com.kage.payment.domain.service.PixBillingGateway;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;

/**
 * O PIX copia e cola da fatura do cartão visto pelo PIX da conta Kage. Pagar aqui é o mesmo que a
 * liquidação do PIX chegar ({@link InvoiceCharges#settle}), só que na hora.
 */
public class InvoicePixBillingGateway implements PixBillingGateway {

    static final String BENEFICIARY = "Kage (fatura do cartão)";
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MM/yyyy");

    private final InvoiceCharges invoiceCharges;

    public InvoicePixBillingGateway(InvoiceCharges invoiceCharges) {
        this.invoiceCharges = invoiceCharges;
    }

    @Override
    public boolean receives(String pixKey) {
        return invoiceCharges.pixKey().equalsIgnoreCase(pixKey);
    }

    @Override
    public Optional<Billing> find(String code) {
        return invoiceCharges.findPix(code).map(c -> new Billing(c.id(), BENEFICIARY,
                "Fatura do cartão Kage de " + YearMonth.parse(c.referenceMonth()).format(MONTH), c.amount(),
                c.expiresAt(), reason(c.status())));
    }

    @Override
    public void settle(UUID chargeId) {
        invoiceCharges.settle(chargeId, null);
    }

    private static String reason(String status) {
        return switch (status) {
            case "PAID" -> "PIX já foi pago";
            case "CANCELLED" -> "PIX cancelado: um código novo da fatura substituiu este";
            case "EXPIRED" -> "PIX vencido: gere outro na fatura";
            default -> null;
        };
    }
}
