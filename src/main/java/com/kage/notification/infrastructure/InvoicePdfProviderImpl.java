package com.kage.notification.infrastructure;

import com.kage.card.application.usecase.InvoiceDocuments;
import com.kage.notification.domain.service.InvoicePdfProvider;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.NotFoundException;

import java.util.Optional;
import java.util.UUID;

/** O PDF vem do mesmo gerador do download da fatura (mesmo processo). */
public class InvoicePdfProviderImpl implements InvoicePdfProvider {

    private final InvoiceDocuments invoiceDocuments;

    public InvoicePdfProviderImpl(InvoiceDocuments invoiceDocuments) {
        this.invoiceDocuments = invoiceDocuments;
    }

    @Override
    public Optional<Pdf> pdfOf(UUID cardId, String referenceMonth) {
        try {
            InvoiceDocuments.Document document = invoiceDocuments.pdf(cardId, referenceMonth);
            return Optional.of(new Pdf(document.fileName(), document.content()));
        } catch (NotFoundException | BusinessRuleException e) {
            return Optional.empty(); // sumiu ou (ainda) aberta: retentar não muda nada
        }
    }
}
