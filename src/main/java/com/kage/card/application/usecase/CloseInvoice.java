package com.kage.card.application.usecase;

import com.kage.card.domain.entity.Invoice;
import com.kage.card.domain.repository.CardRepository;
import com.kage.card.domain.repository.InvoiceRepository;
import com.kage.shared.domain.exception.NotFoundException;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

/**
 * Fecha a fatura de um mês (OPEN -> CLOSED): a partir daí ela não aceita novos lançamentos e passa a
 * poder ser paga. Hoje o fechamento é disparado manualmente (simula o job de fechamento do dia de
 * corte); a regra de que só fatura aberta fecha vive em Invoice.close().
 */
public class CloseInvoice {

    public record Input(UUID cardId, String referenceMonth) {}
    public record Output(UUID invoiceId, UUID cardId, String referenceMonth, String status,
                         BigDecimal total, LocalDate closingDate, LocalDate dueDate) {}

    private final CardRepository cardRepository;
    private final InvoiceRepository invoiceRepository;

    public CloseInvoice(CardRepository cardRepository, InvoiceRepository invoiceRepository) {
        this.cardRepository = cardRepository;
        this.invoiceRepository = invoiceRepository;
    }

    @Transactional
    public Output execute(Input input) {
        YearMonth referenceMonth = ReferenceMonths.parse(input.referenceMonth());

        cardRepository.findById(input.cardId())
                .orElseThrow(() -> new NotFoundException("Cartão não encontrado"));

        Invoice invoice = invoiceRepository.findByCardIdAndReferenceMonth(input.cardId(), referenceMonth)
                .orElseThrow(() -> new NotFoundException("Fatura não encontrada para o mês " + referenceMonth));

        invoice.close();
        Invoice saved = invoiceRepository.save(invoice);

        return new Output(saved.getId(), saved.getCardId(), saved.getReferenceMonth().toString(),
                saved.getStatus().name(), saved.total().amount(), saved.getClosingDate(), saved.getDueDate());
    }
}
