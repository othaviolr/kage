package com.kage.card.domain.entity;

import com.kage.shared.domain.DomainTime;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.valueobject.Money;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Oferta de aumento do limite aprovado, feita pela análise do comportamento do cartão. Vale por
 * {@link #VALIDITY}: aceitar sobe o limite aprovado e o limite em uso; recusar (ou deixar vencer) não
 * muda nada.
 */
public record LimitOffer(UUID id, UUID cardId, Money previousLimit, Money offeredLimit, Status status,
                         LocalDateTime createdAt, LocalDateTime expiresAt, LocalDateTime respondedAt) {

    public enum Status { PENDING, ACCEPTED, DECLINED }

    public static final Duration VALIDITY = Duration.ofDays(30);

    public static LimitOffer create(UUID cardId, Money previousLimit, Money offeredLimit) {
        LocalDateTime now = DomainTime.now();
        return new LimitOffer(UUID.randomUUID(), cardId, previousLimit, offeredLimit, Status.PENDING, now,
                now.plus(VALIDITY), null);
    }

    public boolean isExpired(LocalDateTime now) {
        return status == Status.PENDING && !now.isBefore(expiresAt);
    }

    public boolean isOpen(LocalDateTime now) {
        return status == Status.PENDING && !isExpired(now);
    }

    /** Status para mostrar: EXPIRED não é gravado, sai do relógio. */
    public String displayStatus(LocalDateTime now) {
        return isExpired(now) ? "EXPIRED" : status.name();
    }

    public LimitOffer accept() {
        requireOpen();
        return respond(Status.ACCEPTED);
    }

    public LimitOffer decline() {
        requireOpen();
        return respond(Status.DECLINED);
    }

    private void requireOpen() {
        if (isExpired(DomainTime.now())) throw new BusinessRuleException("A oferta de limite venceu");
        if (status != Status.PENDING) throw new BusinessRuleException("A oferta de limite já foi respondida");
    }

    private LimitOffer respond(Status newStatus) {
        return new LimitOffer(id, cardId, previousLimit, offeredLimit, newStatus, createdAt, expiresAt, DomainTime.now());
    }
}
