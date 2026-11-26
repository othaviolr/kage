package com.kage.card.domain.entity;

import com.kage.card.domain.enums.DisputeReason;
import com.kage.card.domain.enums.DisputeStatus;
import com.kage.shared.domain.DomainTime;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.ValidationException;
import com.kage.shared.domain.valueobject.Money;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Contestação de uma compra no cartão. Enquanto está em análise, a compra não é cobrada: as parcelas
 * em faturas abertas saem delas, e o que já tinha sido cobrado em fatura fechada vira crédito na
 * fatura atual ({@code creditedAmount}). Procedente, fica assim; improcedente, a compra inteira volta
 * a ser cobrada na fatura atual.
 *
 * @param amount         valor total da compra contestada
 * @param creditedAmount parte que já tinha sido cobrada e voltou como crédito (o resto saiu das faturas abertas)
 * @param creditMonth    fatura que recebeu o crédito; nulo se nada tinha sido cobrado ainda
 * @param virtualCardId  compra feita com um cartão virtual: qual; nulo = cartão físico
 * @param virtualCardDeleted "não reconheço" numa compra do virtual: ele foi apagado (o físico segue ativo)
 */
public record Dispute(UUID id, UUID cardId, UUID purchaseId, String description, Money amount, Money creditedAmount,
                      String creditMonth, DisputeReason reason, String details, DisputeStatus status,
                      boolean cardBlocked, LocalDateTime openedAt, LocalDateTime resolvedAt, String resolutionNote,
                      String reversalMonth, UUID virtualCardId, boolean virtualCardDeleted) {

    public static final int MAX_DETAILS = 500;

    public static Dispute open(UUID cardId, UUID purchaseId, String description, Money amount, Money creditedAmount,
                               String creditMonth, DisputeReason reason, String details, boolean cardBlocked,
                               UUID virtualCardId, boolean virtualCardDeleted) {
        String cleanDetails = validDetails(reason, details);
        return new Dispute(UUID.randomUUID(), cardId, purchaseId, description, amount, creditedAmount, creditMonth,
                reason, cleanDetails, DisputeStatus.OPEN, cardBlocked, DomainTime.now(), null, null, null, virtualCardId,
                virtualCardDeleted);
    }

    /**
     * Motivo obrigatório; detalhes também, menos na fraude (quem não reconhece a compra não tem o que
     * contar). Os use cases chamam antes de mexer nas faturas.
     *
     * @return os detalhes sem espaços nas pontas (nulo se não vieram)
     */
    public static String validDetails(DisputeReason reason, String details) {
        if (reason == null) throw new ValidationException("Informe o motivo da contestação (reason)");
        String cleanDetails = details == null || details.isBlank() ? null : details.strip();
        if (cleanDetails != null && cleanDetails.length() > MAX_DETAILS) {
            throw new ValidationException("Detalhes da contestação: no máximo " + MAX_DETAILS + " caracteres");
        }
        if (!reason.isFraud() && cleanDetails == null) {
            throw new ValidationException("Conte o que aconteceu (details): a análise com o estabelecimento depende disso");
        }
        return cleanDetails;
    }

    public Dispute accept(String note) {
        requireOpen();
        return new Dispute(id, cardId, purchaseId, description, amount, creditedAmount, creditMonth, reason, details,
                DisputeStatus.ACCEPTED, cardBlocked, openedAt, DomainTime.now(), clean(note), null, virtualCardId,
                virtualCardDeleted);
    }

    /** O motivo é obrigatório: o cliente tem direito de saber por que vai pagar (CDC, art. 6º, III). */
    public Dispute reject(String note, String reversalMonth) {
        requireOpen();
        String cleanNote = clean(note);
        if (cleanNote == null) throw new ValidationException("Informe o motivo da negativa (note)");
        return new Dispute(id, cardId, purchaseId, description, amount, creditedAmount, creditMonth, reason, details,
                DisputeStatus.REJECTED, cardBlocked, openedAt, DomainTime.now(), cleanNote, reversalMonth,
                virtualCardId, virtualCardDeleted);
    }

    private void requireOpen() {
        if (status != DisputeStatus.OPEN) throw new BusinessRuleException("Contestação já foi resolvida");
    }

    private static String clean(String note) {
        if (note == null || note.isBlank()) return null;
        String stripped = note.strip();
        if (stripped.length() > MAX_DETAILS) {
            throw new ValidationException("Parecer: no máximo " + MAX_DETAILS + " caracteres");
        }
        return stripped;
    }
}
