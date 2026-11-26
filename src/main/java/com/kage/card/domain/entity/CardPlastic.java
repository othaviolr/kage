package com.kage.card.domain.entity;

import com.kage.card.domain.enums.PlasticReason;
import com.kage.card.domain.enums.PlasticStatus;
import com.kage.shared.domain.DomainTime;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.ValidationException;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.UUID;

/**
 * O plástico do cartão físico. O cartão (a conta de crédito: limite, faturas, rotativo) continua o
 * mesmo numa 2ª via; o que muda é o plástico — número, validade e CVV novos. Como no virtual, o número
 * não é guardado: sai do HMAC do id (CardNumberService); aqui ficam os 4 últimos dígitos e o hash.
 *
 * @param sequence 1 = o da emissão; 2 = a 2ª via; e assim por diante
 */
public record CardPlastic(UUID id, UUID cardId, int sequence, String lastDigits, String numberHash, YearMonth expiry,
                          PlasticStatus status, PlasticReason issueReason, LocalDateTime issuedAt,
                          LocalDateTime activatedAt, LocalDateTime cancelledAt, PlasticReason cancelReason) {

    public static final int VALID_YEARS = 5;

    /** O da emissão já nasce ativo (o cliente recebe o cartão desbloqueado, como no cadastro do app). */
    public static CardPlastic first(UUID id, UUID cardId, String lastDigits, String numberHash) {
        LocalDateTime now = DomainTime.now();
        return new CardPlastic(id, cardId, 1, lastDigits, numberHash, YearMonth.from(now).plusYears(VALID_YEARS),
                PlasticStatus.ACTIVE, PlasticReason.FIRST_ISSUE, now, now, null, null);
    }

    public static CardPlastic replacement(UUID id, UUID cardId, int sequence, String lastDigits, String numberHash,
                                          PlasticReason reason) {
        if (reason == null || !reason.isReissueReason()) {
            throw new ValidationException("Motivo da 2ª via inválido: use LOST, STOLEN, FRAUD ou DAMAGED");
        }
        LocalDateTime now = DomainTime.now();
        return new CardPlastic(id, cardId, sequence, lastDigits, numberHash, YearMonth.from(now).plusYears(VALID_YEARS),
                PlasticStatus.PENDING_ACTIVATION, reason, now, null, null, null);
    }

    /** O cliente recebeu o cartão novo e confirma os 4 últimos dígitos impressos nele. */
    public CardPlastic activate(String typedLastDigits) {
        if (status != PlasticStatus.PENDING_ACTIVATION) throw new BusinessRuleException("Não há cartão novo aguardando desbloqueio");
        if (typedLastDigits == null || !typedLastDigits.strip().matches("\\d{4}")) {
            throw new ValidationException("Informe os 4 últimos dígitos do cartão novo (lastDigits)");
        }
        if (!typedLastDigits.strip().equals(lastDigits)) {
            throw new BusinessRuleException("Os 4 últimos dígitos não conferem com o cartão enviado");
        }
        return new CardPlastic(id, cardId, sequence, lastDigits, numberHash, expiry, PlasticStatus.ACTIVE, issueReason,
                issuedAt, DomainTime.now(), null, null);
    }

    public CardPlastic cancel(PlasticReason reason) {
        if (status == PlasticStatus.CANCELLED) throw new BusinessRuleException("Cartão físico já está cancelado");
        return new CardPlastic(id, cardId, sequence, lastDigits, numberHash, expiry, PlasticStatus.CANCELLED, issueReason,
                issuedAt, activatedAt, DomainTime.now(), reason);
    }

    public boolean isActive() {
        return status == PlasticStatus.ACTIVE;
    }

    public boolean isPending() {
        return status == PlasticStatus.PENDING_ACTIVATION;
    }
}
