package com.kage.card.domain.service;

import com.kage.card.domain.entity.Card;
import com.kage.card.domain.entity.Invoice;
import com.kage.shared.domain.valueobject.Money;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Cartão de 1000, fecha dia 10, vence dia 20. */
class CreditLimitReviewPolicyTest {

    private static final LocalDate TODAY = LocalDate.of(2034, 4, 25);

    private final Card card = Card.create(UUID.randomUUID(), UUID.randomUUID(), Money.of("1000.00"), 10, 20);

    /** Fatura do mês com {@code spent} de compras, paga {@code daysLate} dias depois do vencimento (negativo = antes). */
    private Invoice paid(int month, String spent, int daysLate) {
        Invoice invoice = Invoice.open(card.getId(), YearMonth.of(2034, month), 10, 20);
        invoice.addItem(UUID.randomUUID(), "Mercado", Money.of(spent), LocalDateTime.of(2034, month, 5, 10, 0));
        invoice.close();
        invoice.registerPayment(invoice.total(), invoice.getDueDate().plusDays(daysLate).atTime(10, 0));
        return invoice;
    }

    private List<Invoice> goodHistory() {
        return new ArrayList<>(List.of(paid(1, "400.00", -2), paid(2, "350.00", 0), paid(3, "500.00", -5)));
    }

    @Test
    void bomPagador_queUsaOCartao_recebeMais25PorCento() {
        CreditLimitReviewPolicy.Review review = CreditLimitReviewPolicy.review(card, goodHistory(), TODAY, null);

        assertThat(review.approved()).isTrue();
        assertThat(review.reasons()).isEmpty();
        assertThat(review.offeredLimit().amount()).isEqualByComparingTo("1250.00");
    }

    @Test
    void faturaPagaComAtraso_naoPassa() {
        List<Invoice> history = List.of(paid(1, "400.00", -2), paid(2, "350.00", 3), paid(3, "500.00", -5));

        CreditLimitReviewPolicy.Review review = CreditLimitReviewPolicy.review(card, history, TODAY, null);

        assertThat(review.approved()).isFalse();
        assertThat(review.reasons()).anySatisfy(r -> assertThat(r).contains("até o vencimento"));
    }

    @Test
    void historicoCurto_poucoUso_eOfertaRecente_explicamOMotivo() {
        List<Invoice> short2 = List.of(paid(2, "350.00", 0), paid(3, "500.00", -5));
        assertThat(CreditLimitReviewPolicy.review(card, short2, TODAY, null).reasons())
                .anySatisfy(r -> assertThat(r).contains("3 faturas vencidas"));

        List<Invoice> lowUse = List.of(paid(1, "50.00", 0), paid(2, "60.00", 0), paid(3, "40.00", 0));
        assertThat(CreditLimitReviewPolicy.review(card, lowUse, TODAY, null).reasons())
                .anySatisfy(r -> assertThat(r).contains("30% do limite"));

        assertThat(CreditLimitReviewPolicy.review(card, goodHistory(), TODAY, TODAY.minusDays(30).atStartOfDay()).reasons())
                .anySatisfy(r -> assertThat(r).contains("nova análise a partir de 2034-06-24"));
    }

    @Test
    void faturaAindaNaoVencida_naoConta() {
        List<Invoice> history = goodHistory();
        // em 15/03 a fatura de março (vence 20/03) ainda não venceu: só jan e fev contam → histórico curto
        CreditLimitReviewPolicy.Review review = CreditLimitReviewPolicy.review(card, history, LocalDate.of(2034, 3, 15), null);

        assertThat(review.approved()).isFalse();
        assertThat(review.reasons()).anySatisfy(r -> assertThat(r).contains("(há 2)"));
    }

    @Test
    void proximoLimite_arredondaETemTeto() {
        assertThat(CreditLimitReviewPolicy.nextLimit(Money.of("1000.00")).amount()).isEqualByComparingTo("1250.00");
        assertThat(CreditLimitReviewPolicy.nextLimit(Money.of("1010.00")).amount()).isEqualByComparingTo("1300.00"); // 1262,50 → 1300
        assertThat(CreditLimitReviewPolicy.nextLimit(Money.of("60000.00")).amount()).isEqualByComparingTo("70000.00"); // +10 mil por vez
        assertThat(CreditLimitReviewPolicy.nextLimit(Money.of("95000.00")).amount()).isEqualByComparingTo("100000.00"); // teto
    }
}
