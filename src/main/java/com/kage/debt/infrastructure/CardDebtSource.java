package com.kage.debt.infrastructure;

import com.kage.card.application.usecase.AnticipateInstallments;
import com.kage.card.domain.entity.Card;
import com.kage.card.domain.entity.Invoice;
import com.kage.card.domain.enums.InvoiceItemKind;
import com.kage.card.domain.enums.InvoiceStatus;
import com.kage.card.domain.repository.CardRepository;
import com.kage.card.domain.repository.InvoiceRepository;
import com.kage.card.domain.service.CetPolicy;
import com.kage.card.domain.service.InstallmentPolicy;
import com.kage.card.domain.service.RevolvingCreditPolicy;
import com.kage.debt.domain.Debt;
import com.kage.debt.domain.DebtKind;
import com.kage.debt.domain.DebtSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * O que o cliente deve no cartão, sem contar duas vezes: cada real em uso do limite está em uma
 * fatura fechada com saldo, na fatura atual (a aberta mais antiga) ou numa parcela de fatura futura.
 * As parcelas futuras saem da antecipação, que já sabe o valor de cada uma hoje (a valor presente,
 * pela taxa do contrato, no parcelamento da fatura; valor de face na compra sem juros).
 */
public class CardDebtSource implements DebtSource {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MM/yyyy");

    private final CardRepository cardRepository;
    private final InvoiceRepository invoiceRepository;
    private final AnticipateInstallments anticipateInstallments;

    public CardDebtSource(CardRepository cardRepository, InvoiceRepository invoiceRepository,
                          AnticipateInstallments anticipateInstallments) {
        this.cardRepository = cardRepository;
        this.invoiceRepository = invoiceRepository;
        this.anticipateInstallments = anticipateInstallments;
    }

    @Override
    public List<Debt> debtsOf(UUID customerId, LocalDate today) {
        List<Debt> debts = new ArrayList<>();
        for (Card card : cardRepository.findByCustomerId(customerId)) {
            debts.addAll(debtsOf(card, today));
        }
        return debts;
    }

    private List<Debt> debtsOf(Card card, LocalDate today) {
        List<Invoice> invoices = invoiceRepository.findByCardId(card.getId());
        List<Debt> debts = new ArrayList<>();

        invoices.stream()
                .filter(i -> i.getStatus() == InvoiceStatus.CLOSED && !i.remaining().isZero())
                .sorted(Comparator.comparing(Invoice::getReferenceMonth))
                .forEach(i -> debts.add(closedInvoice(card, i, today)));

        Invoice current = invoices.stream()
                .filter(i -> i.getStatus() == InvoiceStatus.OPEN)
                .min(Comparator.comparing(Invoice::getReferenceMonth))
                .orElse(null);
        if (current == null) return debts;
        if (!current.total().isZero()) {
            debts.add(new Debt(DebtKind.CARD_CURRENT_INVOICE, current.getId(), card.getId(),
                    "Fatura atual (" + current.getReferenceMonth().format(MONTH) + ")", current.total().amount(),
                    current.total().amount(), current.getDueDate(), current.total().amount(), null, BigDecimal.ZERO,
                    null, false, null, current.getClosingDate(), 0));
        }

        for (AnticipateInstallments.Plan plan : anticipateInstallments.listPlans(card.getId())) {
            debts.add(plan(card, plan, today));
        }
        return debts;
    }

    private static Debt closedInvoice(Card card, Invoice invoice, LocalDate today) {
        // fatura que já carrega o saldo de uma anterior: o rotativo está correndo nela
        boolean revolving = invoice.getItems().stream().anyMatch(i -> i.kind() == InvoiceItemKind.PREVIOUS_BALANCE);
        BigDecimal rate = revolving ? RevolvingCreditPolicy.REVOLVING_MONTHLY_RATE : BigDecimal.ZERO;
        BigDecimal cet = revolving ? CetPolicy.revolvingReference(today).yearly() : null;
        BigDecimal remaining = invoice.remaining().amount();
        return new Debt(DebtKind.CARD_INVOICE, invoice.getId(), card.getId(),
                "Fatura de " + invoice.getReferenceMonth().format(MONTH) + (revolving ? " (com rotativo)" : ""),
                remaining, remaining, invoice.getDueDate(), remaining, null, rate, cet, invoice.isOverdue(today),
                invoice.minimumPayment().amount(), null, 0);
    }

    private static Debt plan(Card card, AnticipateInstallments.Plan plan, LocalDate today) {
        boolean financing = InvoiceItemKind.INVOICE_INSTALLMENT.name().equals(plan.kind());
        AnticipateInstallments.FutureInstallment next = plan.futureInstallments().get(0);
        LocalDate nextDue = Invoice.dueDateOf(YearMonth.parse(next.referenceMonth()), card.getClosingDay(), card.getDueDay());
        // CET pela tabela; se o teto legal cortou os juros, a taxa é outra e o CET da tabela não vale
        BigDecimal cet = financing && plan.monthlyRate().compareTo(InstallmentPolicy.INVOICE_MONTHLY_RATE) == 0
                ? CetPolicy.installmentReference(today, plan.installmentCount()).yearly()
                : null;
        return new Debt(financing ? DebtKind.CARD_INVOICE_FINANCING : DebtKind.CARD_PURCHASE_INSTALLMENTS,
                plan.planId(), card.getId(), plan.description(), plan.futureTotal(), plan.anticipateAllAmount(), nextDue,
                next.amount(), plan.futureInstallments().size(), plan.monthlyRate(), cet, false, null, null, 0);
    }
}
