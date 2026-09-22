package com.kage.card.application.usecase;

import com.kage.card.domain.entity.Card;
import com.kage.card.domain.entity.Invoice;
import com.kage.card.domain.repository.CardRepository;
import com.kage.card.domain.repository.InvoiceRepository;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.exception.ValidationException;
import com.kage.shared.domain.valueobject.Money;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.UUID;

public class RegisterPurchase {

    public record Input(UUID cardId, UUID purchaseId, String description, BigDecimal amount, LocalDateTime purchasedAt) {}
    public record Output(UUID cardId, BigDecimal availableLimit, UUID invoiceId, String referenceMonth,
                         BigDecimal invoiceTotal, boolean duplicate) {}

    private final CardRepository cardRepository;
    private final InvoiceRepository invoiceRepository;

    public RegisterPurchase(CardRepository cardRepository, InvoiceRepository invoiceRepository) {
        this.cardRepository = cardRepository;
        this.invoiceRepository = invoiceRepository;
    }

    @Transactional
    public Output execute(Input input) {
        if (input.amount() == null || input.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ValidationException("Valor da compra deve ser maior que zero");
        }

        Card card = cardRepository.findById(input.cardId())
                .orElseThrow(() -> new NotFoundException("Cartão não encontrado"));

        YearMonth referenceMonth = Invoice.referenceMonthFor(input.purchasedAt().toLocalDate(), card.getClosingDay());
        Invoice invoice = invoiceRepository.findByCardIdAndReferenceMonth(card.getId(), referenceMonth)
                .orElseGet(() -> Invoice.open(card.getId(), referenceMonth, card.getClosingDay(), card.getDueDay()));

        boolean alreadyRegistered = invoice.getItems().stream()
                .anyMatch(item -> item.purchaseId().equals(input.purchaseId()));

        if (alreadyRegistered) {
            return toOutput(card, invoice, true);
        }

        card.authorizePurchase(new Money(input.amount()));
        invoice.addItem(input.purchaseId(), input.description(), new Money(input.amount()), input.purchasedAt());

        Card savedCard = cardRepository.save(card);
        Invoice savedInvoice = invoiceRepository.save(invoice);

        return toOutput(savedCard, savedInvoice, false);
    }

    private Output toOutput(Card card, Invoice invoice, boolean duplicate) {
        return new Output(card.getId(), card.availableLimit().amount(), invoice.getId(),
                invoice.getReferenceMonth().toString(), invoice.total().amount(), duplicate);
    }
}
