package com.kage.card.application.usecase;

import com.kage.card.domain.entity.Invoice;
import com.kage.card.domain.repository.CardRepository;
import com.kage.card.domain.repository.InvoiceRepository;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.exception.ValidationException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

public class GetInvoice {

    public record Input(UUID cardId, String referenceMonth) {}
    public record ItemOutput(UUID id, UUID purchaseId, String description, BigDecimal amount, LocalDateTime purchasedAt) {}
    public record Output(UUID invoiceId, UUID cardId, String referenceMonth, LocalDate closingDate, LocalDate dueDate,
                         String status, BigDecimal total, LocalDateTime paidAt, List<ItemOutput> items) {}

    private final CardRepository cardRepository;
    private final InvoiceRepository invoiceRepository;

    public GetInvoice(CardRepository cardRepository, InvoiceRepository invoiceRepository) {
        this.cardRepository = cardRepository;
        this.invoiceRepository = invoiceRepository;
    }

    public Output execute(Input input) {
        YearMonth referenceMonth = parse(input.referenceMonth());

        cardRepository.findById(input.cardId())
                .orElseThrow(() -> new NotFoundException("Cartão não encontrado"));

        Invoice invoice = invoiceRepository.findByCardIdAndReferenceMonth(input.cardId(), referenceMonth)
                .orElseThrow(() -> new NotFoundException("Fatura não encontrada para o mês " + referenceMonth));

        List<ItemOutput> items = invoice.getItems().stream()
                .map(item -> new ItemOutput(item.id(), item.purchaseId(), item.description(),
                        item.amount().amount(), item.purchasedAt()))
                .toList();

        return new Output(invoice.getId(), invoice.getCardId(), invoice.getReferenceMonth().toString(),
                invoice.getClosingDate(), invoice.getDueDate(), invoice.getStatus().name(),
                invoice.total().amount(), invoice.getPaidAt(), items);
    }

    private YearMonth parse(String referenceMonth) {
        try {
            return YearMonth.parse(referenceMonth);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new ValidationException("Mês de referência inválido. Use o formato yyyy-MM");
        }
    }
}
