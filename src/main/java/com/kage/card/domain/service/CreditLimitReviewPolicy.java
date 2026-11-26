package com.kage.card.domain.service;

import com.kage.card.domain.entity.Card;
import com.kage.card.domain.entity.Invoice;
import com.kage.card.domain.enums.CardStatus;
import com.kage.card.domain.enums.InvoiceStatus;
import com.kage.shared.domain.valueobject.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Análise de aumento do limite pelo comportamento do cartão — o que um banco olha antes de oferecer
 * mais crédito: histórico de faturas pagas em dia, sem rotativo nem parcelamento, e uso do limite
 * (quem não usa não precisa de mais). Aprovado, o limite sobe {@link #INCREASE} (arredondado para cima
 * em múltiplos de R$ 50), no máximo {@link #MAX_STEP} por vez e até {@link #MAX_LIMIT}.
 */
public final class CreditLimitReviewPolicy {

    public static final int REQUIRED_ON_TIME = 3;
    public static final int CLEAN_HISTORY = 6;
    public static final BigDecimal MIN_USAGE = new BigDecimal("0.30");
    public static final BigDecimal INCREASE = new BigDecimal("1.25");
    public static final Money MAX_STEP = Money.of("10000.00");
    public static final Money MAX_LIMIT = Money.of("100000.00");
    public static final int COOLDOWN_DAYS = 90;
    private static final BigDecimal ROUND_TO = new BigDecimal("50");

    /** @param reasons por que não (vazio quando aprovado) — o cliente vê o que fazer para chegar lá */
    public record Review(boolean approved, Money offeredLimit, List<String> reasons) {}

    private CreditLimitReviewPolicy() {
    }

    /** @param lastOfferAt quando foi a última oferta (nula se nunca houve) */
    public static Review review(Card card, List<Invoice> invoices, LocalDate today, LocalDateTime lastOfferAt) {
        List<String> reasons = new ArrayList<>();
        if (card.getStatus() != CardStatus.ACTIVE) reasons.add("O cartão precisa estar ativo");
        if (lastOfferAt != null && ChronoUnit.DAYS.between(lastOfferAt.toLocalDate(), today) < COOLDOWN_DAYS) {
            reasons.add("Já houve uma oferta nos últimos " + COOLDOWN_DAYS + " dias: nova análise a partir de "
                    + lastOfferAt.toLocalDate().plusDays(COOLDOWN_DAYS));
        }
        if (card.hasRevolvingDebt()) reasons.add("Quite o saldo do rotativo antes");

        // faturas já vencidas, da mais recente para a mais antiga
        List<Invoice> due = invoices.stream()
                .filter(i -> i.getStatus() != InvoiceStatus.OPEN && i.getDueDate().isBefore(today))
                .sorted(Comparator.comparing(Invoice::getReferenceMonth).reversed())
                .toList();
        List<Invoice> recent = due.stream().limit(REQUIRED_ON_TIME).toList();
        if (recent.size() < REQUIRED_ON_TIME) {
            reasons.add("São precisas " + REQUIRED_ON_TIME + " faturas vencidas no histórico (há " + recent.size() + ")");
        } else if (!recent.stream().allMatch(CreditLimitReviewPolicy::paidOnTime)) {
            reasons.add("Pague as " + REQUIRED_ON_TIME + " últimas faturas inteiras até o vencimento");
        }
        boolean troubled = due.stream().limit(CLEAN_HISTORY).anyMatch(i -> i.getStatus() == InvoiceStatus.ROLLED_OVER
                || i.getStatus() == InvoiceStatus.FINANCED || i.getStatus() == InvoiceStatus.CLOSED);
        if (troubled) reasons.add("Sem rotativo, parcelamento de fatura ou atraso nas últimas " + CLEAN_HISTORY + " faturas");

        if (!recent.isEmpty()) {
            BigDecimal average = recent.stream().map(i -> i.charges().amount()).reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(recent.size()), 2, RoundingMode.HALF_UP);
            if (average.compareTo(card.getCreditLimit().amount().multiply(MIN_USAGE)) < 0) {
                reasons.add("Use pelo menos " + MIN_USAGE.movePointRight(2).intValue() + "% do limite por mês");
            }
        }
        if (!card.getApprovedLimit().isLessThan(MAX_LIMIT)) reasons.add("O limite já está no máximo do produto");

        if (!reasons.isEmpty()) return new Review(false, null, reasons);
        return new Review(true, nextLimit(card.getApprovedLimit()), List.of());
    }

    /** +25%, arredondado para cima em R$ 50, no máximo +R$ 10 mil por vez e até R$ 100 mil. */
    public static Money nextLimit(Money approved) {
        BigDecimal raised = approved.amount().multiply(INCREASE)
                .divide(ROUND_TO, 0, RoundingMode.CEILING).multiply(ROUND_TO);
        BigDecimal step = raised.subtract(approved.amount()).min(MAX_STEP.amount());
        return Money.of(approved.amount().add(step).min(MAX_LIMIT.amount()));
    }

    private static boolean paidOnTime(Invoice invoice) {
        return invoice.getStatus() == InvoiceStatus.PAID && invoice.getPaidAt() != null
                && !invoice.getPaidAt().toLocalDate().isAfter(invoice.getDueDate());
    }
}
