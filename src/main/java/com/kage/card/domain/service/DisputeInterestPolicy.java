package com.kage.card.domain.service;

import com.kage.card.domain.entity.Invoice;
import com.kage.card.domain.enums.InvoiceItemKind;
import com.kage.card.domain.enums.InvoiceStatus;
import com.kage.card.domain.valueobject.InvoiceItem;
import com.kage.shared.domain.valueobject.Money;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Quanto dos juros e encargos cobrados no cartão veio de uma compra contestada procedente — o que o
 * banco devolve, já que a compra nunca deveria ter sido cobrada.
 *
 * A compra entra numa fatura fechada. Se ela foi quitada, não houve encargo. Se o saldo foi para o
 * rotativo, a fatura seguinte trouxe "Saldo anterior" com juros, multa e mora; se foi parcelada, os
 * juros estão nas parcelas. A parte da compra é proporcional: valor dela ÷ tudo o que a fatura
 * cobrava (o pagamento parcial abate cada linha na mesma proporção). E a conta segue a corrente: a
 * parte da compra que foi para a fatura seguinte (com os encargos dela) pode ter girado de novo.
 */
public final class DisputeInterestPolicy {

    private static final MathContext RATIO = new MathContext(16, RoundingMode.HALF_EVEN);
    private static final List<InvoiceItemKind> CHARGES =
            List.of(InvoiceItemKind.REVOLVING_INTEREST, InvoiceItemKind.LATE_FINE, InvoiceItemKind.LATE_INTEREST);
    /** Uma fatura não gira mais que isso numa corrente real (o Bacen parcela depois de um ciclo). */
    private static final int MAX_DEPTH = 24;

    private DisputeInterestPolicy() {
    }

    /** Encargos atribuíveis à compra {@code purchaseId}, somando todas as faturas fechadas em que ela foi cobrada. */
    public static Money refundFor(List<Invoice> invoices, UUID purchaseId) {
        BigDecimal total = BigDecimal.ZERO;
        for (Invoice invoice : invoices) {
            if (invoice.getStatus() == InvoiceStatus.OPEN) continue;
            for (InvoiceItem item : invoice.getItems()) {
                if (item.isPurchase() && item.purchaseId().equals(purchaseId)) {
                    total = total.add(chargesAttributable(invoices, invoice, item.amount().amount(), 0));
                }
            }
        }
        return Money.of(total.setScale(2, RoundingMode.HALF_UP));
    }

    /** Encargos que {@code share} (parte da compra cobrada em {@code invoice}) gerou dali em diante. */
    private static BigDecimal chargesAttributable(List<Invoice> invoices, Invoice invoice, BigDecimal share, int depth) {
        if (depth >= MAX_DEPTH || share.signum() <= 0) return BigDecimal.ZERO;
        BigDecimal charges = invoice.charges().amount();
        if (charges.signum() <= 0) return BigDecimal.ZERO;
        BigDecimal ratio = share.min(charges).divide(charges, RATIO);

        if (invoice.getStatus() == InvoiceStatus.ROLLED_OVER) {
            Optional<Invoice> next = invoiceWith(invoices, InvoiceItem.chargeId(InvoiceItemKind.PREVIOUS_BALANCE, invoice.getId()));
            if (next.isEmpty()) return BigDecimal.ZERO;
            BigDecimal carried = lineAmount(next.get(), InvoiceItem.chargeId(InvoiceItemKind.PREVIOUS_BALANCE, invoice.getId()));
            BigDecimal levied = BigDecimal.ZERO;
            for (InvoiceItemKind kind : CHARGES) {
                levied = levied.add(lineAmount(next.get(), InvoiceItem.chargeId(kind, invoice.getId())));
            }
            BigDecimal mine = levied.multiply(ratio);
            BigDecimal carriedShare = carried.multiply(ratio).add(mine);
            return mine.add(chargesAttributable(invoices, next.get(), carriedShare, depth + 1));
        }

        if (invoice.getStatus() == InvoiceStatus.FINANCED) {
            return financedInterest(invoices, invoice).multiply(ratio);
        }
        return BigDecimal.ZERO; // quitada (ou fechada ainda no prazo): sem encargo
    }

    /**
     * Juros do parcelamento da fatura: o que as parcelas somam acima do saldo parcelado. Parcelas que
     * já foram antecipadas saíram do plano com o desconto dos juros, então só contam as que restam.
     */
    private static BigDecimal financedInterest(List<Invoice> invoices, Invoice source) {
        UUID planId = InvoiceItem.invoiceInstallmentPlanId(source.getId());
        List<InvoiceItem> installments = invoices.stream()
                .flatMap(invoice -> invoice.getItems().stream())
                .filter(item -> item.purchaseId().equals(planId) && item.kind() == InvoiceItemKind.INVOICE_INSTALLMENT)
                .toList();
        if (installments.isEmpty()) return BigDecimal.ZERO;
        int count = installments.get(0).installmentCount();
        BigDecimal principal = source.remaining().amount();
        BigDecimal found = installments.stream().map(i -> i.amount().amount()).reduce(BigDecimal.ZERO, BigDecimal::add);
        // Tabela Price: parcelas iguais (a diferença é de centavo) — o plano inteiro é a média × N
        BigDecimal planned = found.multiply(BigDecimal.valueOf(count)).divide(BigDecimal.valueOf(installments.size()), RATIO);
        BigDecimal interest = planned.subtract(principal);
        if (interest.signum() <= 0) return BigDecimal.ZERO;
        // juros por parcela × parcelas que ainda estão no plano
        return interest.multiply(BigDecimal.valueOf(installments.size())).divide(BigDecimal.valueOf(count), RATIO);
    }

    private static Optional<Invoice> invoiceWith(List<Invoice> invoices, UUID lineId) {
        return invoices.stream().filter(i -> i.getItems().stream().anyMatch(item -> item.purchaseId().equals(lineId))).findFirst();
    }

    private static BigDecimal lineAmount(Invoice invoice, UUID lineId) {
        return invoice.getItems().stream().filter(item -> item.purchaseId().equals(lineId))
                .map(item -> item.amount().amount()).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
