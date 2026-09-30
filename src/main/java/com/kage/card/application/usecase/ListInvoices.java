package com.kage.card.application.usecase;

import com.kage.card.domain.entity.Invoice;
import com.kage.card.domain.repository.CardRepository;
import com.kage.card.domain.repository.InvoiceRepository;
import com.kage.shared.domain.exception.NotFoundException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public class ListInvoices {

    public record Input(UUID cardId) {}
    public record InvoiceSummary(UUID invoiceId, String referenceMonth, LocalDate closingDate, LocalDate dueDate,
                                 String status, BigDecimal total, int itemCount) {}
    public record Output(UUID cardId, List<InvoiceSummary> invoices) {}

    private final CardRepository cardRepository;
    private final InvoiceRepository invoiceRepository;

    public ListInvoices(CardRepository cardRepository, InvoiceRepository invoiceRepository) {
        this.cardRepository = cardRepository;
        this.invoiceRepository = invoiceRepository;
    }

    public Output execute(Input input) {
        cardRepository.findById(input.cardId())
                .orElseThrow(() -> new NotFoundException("Cartão não encontrado"));

        List<InvoiceSummary> invoices = invoiceRepository.findByCardId(input.cardId()).stream()
                .map(this::toSummary)
                .toList();

        return new Output(input.cardId(), invoices);
    }

    private InvoiceSummary toSummary(Invoice invoice) {
        return new InvoiceSummary(invoice.getId(), invoice.getReferenceMonth().toString(), invoice.getClosingDate(),
                invoice.getDueDate(), invoice.getStatus().name(), invoice.total().amount(), invoice.getItems().size());
    }
}
