package com.kage.card.application.usecase;

import com.kage.card.domain.entity.Card;
import com.kage.card.domain.entity.Invoice;
import com.kage.card.domain.enums.InvoiceStatus;
import com.kage.card.domain.repository.CardRepository;
import com.kage.card.domain.repository.InvoiceRepository;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.exception.ValidationException;
import com.kage.shared.domain.valueobject.Money;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import com.kage.card.domain.service.InstallmentPolicy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class RegisterPurchase {

    /** @param installments parcelas sem juros (1 = à vista; nulo também) */
    public record Input(UUID cardId, UUID purchaseId, String description, BigDecimal amount, LocalDateTime purchasedAt,
                        Integer installments) {
        public Input(UUID cardId, UUID purchaseId, String description, BigDecimal amount, LocalDateTime purchasedAt) {
            this(cardId, purchaseId, description, amount, purchasedAt, 1);
        }
    }

    /** @param invoiceId fatura da 1ª parcela; {@code installmentAmount} é o valor dela */
    public record Output(UUID cardId, BigDecimal availableLimit, UUID invoiceId, String referenceMonth,
                         BigDecimal invoiceTotal, int installments, BigDecimal installmentAmount, boolean duplicate) {}

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

        Optional<Invoice> alreadyRegistered = invoiceRepository.findByCardIdAndPurchaseId(card.getId(), input.purchaseId());
        if (alreadyRegistered.isPresent()) {
            return toOutput(card, alreadyRegistered.get(), input.purchaseId(), true);
        }

        int installments = input.installments() == null ? 1 : input.installments();
        Money total = new Money(input.amount());
        List<Money> parts = InstallmentPolicy.splitWithoutInterest(total, installments);

        // o limite é consumido pelo valor TOTAL na hora (como nos cartões de verdade) e volta
        // conforme cada fatura é paga
        card.authorizePurchase(total);

        Invoice first = null;
        YearMonth month = Invoice.referenceMonthFor(input.purchasedAt().toLocalDate(), card.getClosingDay());
        for (int number = 1; number <= installments; number++) {
            Invoice invoice = findOpenInvoiceFrom(card, month);
            String description = installments == 1
                    ? input.description()
                    : input.description() + " (" + number + "/" + installments + ")";
            invoice.addInstallment(input.purchaseId(), description, parts.get(number - 1), input.purchasedAt(),
                    number, installments);
            Invoice saved = invoiceRepository.save(invoice);
            if (first == null) first = saved;
            month = saved.getReferenceMonth().plusMonths(1); // a próxima parcela vai para o mês seguinte
        }

        Card savedCard = cardRepository.save(card);
        return toOutput(savedCard, first, input.purchaseId(), false);
    }

    /**
     * Fatura que recebe a parcela: a do mês informado e, se ela já foi fechada (ou paga), a primeira
     * fatura seguinte que ainda esteja aberta, criando-a se não existir. É o que um banco faz com a
     * compra que chega depois do fechamento — e é assim que as faturas futuras de uma compra
     * parcelada passam a existir.
     */
    private Invoice findOpenInvoiceFrom(Card card, YearMonth from) {
        YearMonth month = from;
        while (true) {
            Optional<Invoice> existing = invoiceRepository.findByCardIdAndReferenceMonth(card.getId(), month);
            if (existing.isEmpty()) {
                return Invoice.open(card.getId(), month, card.getClosingDay(), card.getDueDay());
            }
            if (existing.get().getStatus() == InvoiceStatus.OPEN) {
                return existing.get();
            }
            month = month.plusMonths(1);
        }
    }

    private Output toOutput(Card card, Invoice invoice, UUID purchaseId, boolean duplicate) {
        var item = invoice.getItems().stream().filter(i -> i.purchaseId().equals(purchaseId)).findFirst().orElseThrow();
        return new Output(card.getId(), card.availableLimit().amount(), invoice.getId(),
                invoice.getReferenceMonth().toString(), invoice.total().amount(), item.installmentCount(),
                item.amount().amount(), duplicate);
    }
}
