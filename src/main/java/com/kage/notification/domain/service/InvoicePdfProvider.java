package com.kage.notification.domain.service;

import java.util.Optional;
import java.util.UUID;

/** O PDF da fatura fechada, para anexar no e-mail. Implementado em cima do contexto de cartão. */
public interface InvoicePdfProvider {

    record Pdf(String fileName, byte[] content) {}

    /** Vazio se a fatura não existe ou ainda está aberta. */
    Optional<Pdf> pdfOf(UUID cardId, String referenceMonth);
}
