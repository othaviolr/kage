package com.kage.card.domain.entity;

import com.kage.card.domain.enums.VirtualCardKind;
import com.kage.card.domain.enums.VirtualCardStatus;
import com.kage.shared.domain.DomainTime;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.ValidationException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.UUID;

/**
 * Cartão virtual: número próprio para compras online, ligado ao cartão físico — usa o mesmo limite e
 * as compras caem na mesma fatura. Apagar um virtual (número vazou, assinatura que não quer mais) não
 * afeta o físico nem os outros virtuais; bloquear o físico bloqueia todos.
 *
 * O número completo não é guardado: ele é derivado do id (ver CardNumberService). Aqui ficam só os
 * quatro últimos dígitos, para mostrar, e o hash, para achar o cartão quando uma compra chega com ele.
 *
 * @param expiresAt temporário: válido por {@link #TEMPORARY_TTL} a partir da criação; nulo no permanente
 */
public record VirtualCard(UUID id, UUID cardId, String nickname, VirtualCardKind kind, String lastDigits,
                          String numberHash, YearMonth expiry, VirtualCardStatus status, LocalDateTime createdAt,
                          LocalDateTime expiresAt, LocalDateTime deletedAt) {

    public static final int MAX_NICKNAME = 30;
    /** O temporário serve para uma compra pontual num site em que o cliente não confia. */
    public static final Duration TEMPORARY_TTL = Duration.ofHours(24);
    /** Validade impressa (mês/ano) do permanente. */
    public static final int VALID_YEARS = 5;

    public static VirtualCard create(UUID id, UUID cardId, String nickname, VirtualCardKind kind, String lastDigits,
                                     String numberHash) {
        if (kind == null) throw new ValidationException("Informe o tipo do cartão virtual (kind): PERMANENT ou TEMPORARY");
        String clean = nickname == null ? "" : nickname.strip();
        if (clean.isEmpty()) throw new ValidationException("Dê um nome ao cartão virtual (nickname)");
        if (clean.length() > MAX_NICKNAME) {
            throw new ValidationException("Nome do cartão virtual: no máximo " + MAX_NICKNAME + " caracteres");
        }
        LocalDateTime now = DomainTime.now();
        LocalDateTime expiresAt = kind == VirtualCardKind.TEMPORARY ? now.plus(TEMPORARY_TTL) : null;
        YearMonth expiry = kind == VirtualCardKind.TEMPORARY
                ? YearMonth.from(expiresAt)
                : YearMonth.from(now).plusYears(VALID_YEARS);
        return new VirtualCard(id, cardId, clean, kind, lastDigits, numberHash, expiry, VirtualCardStatus.ACTIVE,
                now, expiresAt, null);
    }

    /** Temporário que passou das 24h. */
    public boolean isExpired(LocalDateTime now) {
        if (expiresAt != null && !now.isBefore(expiresAt)) return true;
        return YearMonth.from(now).isAfter(expiry);
    }

    /** Status para mostrar: EXPIRED não é gravado, sai do relógio. */
    public String displayStatus(LocalDateTime now) {
        if (status == VirtualCardStatus.DELETED) return status.name();
        return isExpired(now) ? "EXPIRED" : status.name();
    }

    /** Pode comprar com ele agora (o físico também precisa estar ativo: isso o Card checa). */
    public void requireUsable(LocalDateTime now) {
        if (status == VirtualCardStatus.DELETED) throw new BusinessRuleException("Cartão virtual foi apagado");
        if (isExpired(now)) throw new BusinessRuleException("Cartão virtual expirado");
        if (status == VirtualCardStatus.BLOCKED) throw new BusinessRuleException("Cartão virtual está bloqueado");
    }

    public VirtualCard block() {
        requireNotDeleted();
        if (status == VirtualCardStatus.BLOCKED) throw new BusinessRuleException("Cartão virtual já está bloqueado");
        return withStatus(VirtualCardStatus.BLOCKED, null);
    }

    public VirtualCard unblock() {
        requireNotDeleted();
        if (status != VirtualCardStatus.BLOCKED) throw new BusinessRuleException("Cartão virtual não está bloqueado");
        if (isExpired(DomainTime.now())) throw new BusinessRuleException("Cartão virtual expirado: crie outro");
        return withStatus(VirtualCardStatus.ACTIVE, null);
    }

    /** Definitivo: o número morre. Para comprar online de novo, cria-se outro (com outro número). */
    public VirtualCard delete() {
        requireNotDeleted();
        return withStatus(VirtualCardStatus.DELETED, DomainTime.now());
    }

    public boolean isDeleted() {
        return status == VirtualCardStatus.DELETED;
    }

    private void requireNotDeleted() {
        if (isDeleted()) throw new BusinessRuleException("Cartão virtual já foi apagado");
    }

    private VirtualCard withStatus(VirtualCardStatus newStatus, LocalDateTime deletedAt) {
        return new VirtualCard(id, cardId, nickname, kind, lastDigits, numberHash, expiry, newStatus, createdAt,
                expiresAt, deletedAt);
    }
}
