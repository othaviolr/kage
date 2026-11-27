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
 * Quanto dos juros, encargos e IOF cobrados no cartão veio de uma compra contestada procedente — o
 * que o banco devolve, já que a compra nunca deveria ter sido cobrada.
 *
 * A compra entra numa fatura fechada. Se ela foi quitada, não houve encargo. Se o saldo foi para o
 * rotativo, a fatura seguinte trouxe "Saldo anterior" com juros, multa, mora e IOF; se foi parcelada,
 * os juros estão nas parcelas e o IOF numa linha própria. A parte da compra é proporcional: valor
 * dela ÷ tudo o que a fatura cobrava (o pagamento parcial abate cada linha na mesma proporção). E a
 * conta segue a corrente: a parte da compra que foi para a fatura seguinte (com os encargos dela) pode
 * ter girado de novo.
 *
 * O IOF vem separado: ele é imposto e não conta no teto da Lei 14.690/2023, então o estorno dele não
 * mexe naquela conta.
 */
public final class DisputeInterestPolicy {

    private static final MathContext RATIO = new MathContext(16, RoundingMode.HALF_EVEN);
    private static final List<InvoiceItemKind> CHARGES =
            List.of(InvoiceItemKind.REVOLVING_INTEREST, InvoiceItemKind.LATE_FINE, InvoiceItemKind.LATE_INTEREST);
    /** Uma fatura não gira mais que isso numa corrente real (o Bacen parcela depois de um ciclo). */
    private static final int MAX_DEPTH = 24;

    /** @param charges juros e encargos do banco; @param iof o imposto */
    public record Refund(Money charges, Money iof) {
        public Money total() {
            return charges.add(iof);
        }
    }

    private DisputeInterestPolicy() {
    }

    /** Encargos e IOF atribuíveis à compra {@code purchaseId}, em todas as faturas fechadas em que ela foi cobrada. */
    public static Refund refundFor(List<Invoice> invoices, UUID purchaseId) {
        Accumulator total = new Accumulator();
        for (Invoice invoice : invoices) {
            if (invoice.getStatus() == InvoiceStatus.OPEN) continue;
            for (InvoiceItem item : invoice.getItems()) {
                if (item.isPurchase() && item.purchaseId().equals(purchaseId)) {
                    // compra internacional: o IOF dela também foi cobrado e também girou no rotativo
                    attribute(invoices, invoice, item.amount().amount().add(purchaseIof(invoice, purchaseId)), 0, total);
                }
            }
        }
        return new Refund(Money.of(total.charges.setScale(2, RoundingMode.HALF_UP)),
                Money.of(total.iof.setScale(2, RoundingMode.HALF_UP)));
    }

    /** A linha de IOF da compra internacional {@code purchaseId} nesta fatura (zero se não há). */
    private static BigDecimal purchaseIof(Invoice invoice, UUID purchaseId) {
        UUID line = InvoiceItem.chargeId(InvoiceItemKind.IOF, purchaseId);
        return invoice.getItems().stream().filter(i -> i.kind() == InvoiceItemKind.IOF && i.purchaseId().equals(line))
                .map(i -> i.amount().amount()).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static final class Accumulator {
        BigDecimal charges = BigDecimal.ZERO;
        BigDecimal iof = BigDecimal.ZERO;
    }

    /** Soma em {@code acc} os encargos e o IOF que {@code share} (parte da compra em {@code invoice}) gerou dali em diante. */
    private static void attribute(List<Invoice> invoices, Invoice invoice, BigDecimal share, int depth, Accumulator acc) {
        if (depth >= MAX_DEPTH || share.signum() <= 0) return;
        BigDecimal charges = invoice.charges().amount();
        if (charges.signum() <= 0) return;
        BigDecimal ratio = share.min(charges).divide(charges, RATIO);

        if (invoice.getStatus() == InvoiceStatus.ROLLED_OVER) {
            UUID balanceLine = InvoiceItem.chargeId(InvoiceItemKind.PREVIOUS_BALANCE, invoice.getId());
            Optional<Invoice> next = invoiceWith(invoices, balanceLine);
            if (next.isEmpty()) return;
            BigDecimal carried = lineAmount(next.get(), balanceLine);
            BigDecimal levied = BigDecimal.ZERO;
            for (InvoiceItemKind kind : CHARGES) {
                levied = levied.add(lineAmount(next.get(), InvoiceItem.chargeId(kind, invoice.getId())));
            }
            BigDecimal mine = levied.multiply(ratio);
            BigDecimal myIof = lineAmount(next.get(), InvoiceItem.chargeId(InvoiceItemKind.IOF, invoice.getId())).multiply(ratio);
            acc.charges = acc.charges.add(mine);
            acc.iof = acc.iof.add(myIof);
            attribute(invoices, next.get(), carried.multiply(ratio).add(mine).add(myIof), depth + 1, acc);
            return;
        }

        if (invoice.getStatus() == InvoiceStatus.FINANCED) {
            acc.charges = acc.charges.add(financedInterest(invoices, invoice).multiply(ratio));
            UUID iofLine = InvoiceItem.chargeId(InvoiceItemKind.IOF, invoice.getId());
            invoiceWith(invoices, iofLine).ifPresent(i -> acc.iof = acc.iof.add(lineAmount(i, iofLine).multiply(ratio)));
        }
        // quitada (ou fechada ainda no prazo): sem encargo
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
