package com.kage.card;

import com.kage.AbstractIntegrationTest;
import com.kage.account.application.usecase.DepositAccount;
import com.kage.card.application.usecase.CloseInvoice;
import com.kage.card.application.usecase.CreditLimits;
import com.kage.card.application.usecase.GetCard;
import com.kage.card.application.usecase.IssueCard;
import com.kage.card.application.usecase.PayInvoice;
import com.kage.card.application.usecase.RegisterPurchase;
import com.kage.shared.domain.DomainTime;
import com.kage.shared.domain.exception.BusinessRuleException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Limite do cartão com Postgres real: ajuste pelo cliente, análise pelo comportamento, oferta e aceite. */
class CreditLimitIntegrationTest extends AbstractIntegrationTest {

    @Autowired IssueCard issueCard;
    @Autowired RegisterPurchase registerPurchase;
    @Autowired CloseInvoice closeInvoice;
    @Autowired PayInvoice payInvoice;
    @Autowired DepositAccount depositAccount;
    @Autowired CreditLimits creditLimits;
    @Autowired GetCard getCard;

    @AfterEach
    void resetClock() {
        DomainTime.reset();
    }

    private static void today(LocalDate date) {
        ZoneId zone = ZoneId.systemDefault();
        DomainTime.use(Clock.fixed(date.atTime(10, 0).atZone(zone).toInstant(), zone));
    }

    /** Cartão de 1000 com jan, fev e mar/2034 usados (400 cada) e pagos inteiros antes do vencimento. */
    private UUID goodPayer() {
        IssueCard.Input input = issueCardInput(new BigDecimal("1000.00"), 10, 20);
        UUID cardId = issueCard.execute(input).cardId();
        depositAccount.execute(new DepositAccount.Input(input.accountId(), new BigDecimal("5000.00")));
        for (int month = 1; month <= 3; month++) {
            registerPurchase.execute(new RegisterPurchase.Input(cardId, UUID.randomUUID(), "Mercado", new BigDecimal("400.00"),
                    LocalDateTime.of(2034, month, 5, 10, 0)));
            closeInvoice.execute(new CloseInvoice.Input(cardId, "2034-0" + month));
            today(LocalDate.of(2034, month, 15)); // paga 5 dias antes do vencimento (dia 20)
            payInvoice.execute(new PayInvoice.Input(cardId, "2034-0" + month));
        }
        today(LocalDate.of(2034, 4, 25));
        return cardId;
    }

    @Test
    void bomPagador_pedeAnalise_recebeOferta_eAceita() {
        UUID cardId = goodPayer();

        CreditLimits.ReviewOutput review = creditLimits.requestReview(cardId);
        assertThat(review.approved()).isTrue();
        assertThat(review.offer().offeredLimit()).isEqualByComparingTo("1250.00");
        assertThat(review.offer().status()).isEqualTo("PENDING");
        assertThat(review.offer().expiresAt()).isEqualTo(LocalDateTime.of(2034, 5, 25, 10, 0));

        // pedir de novo devolve a mesma oferta aberta
        assertThat(creditLimits.requestReview(cardId).offer().offerId()).isEqualTo(review.offer().offerId());

        CreditLimits.Limits limits = creditLimits.accept(cardId, review.offer().offerId());
        assertThat(limits.approvedLimit()).isEqualByComparingTo("1250.00");
        assertThat(limits.creditLimit()).isEqualByComparingTo("1250.00");
        assertThat(getCard.execute(new GetCard.Input(cardId)).approvedLimit()).isEqualByComparingTo("1250.00");
        assertThatThrownBy(() -> creditLimits.accept(cardId, review.offer().offerId()))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("respondida");

        // logo depois: oferta recente, nova análise só em 90 dias
        CreditLimits.ReviewOutput again = creditLimits.requestReview(cardId);
        assertThat(again.approved()).isFalse();
        assertThat(again.reasons()).anySatisfy(r -> assertThat(r).contains("90 dias"));
    }

    @Test
    void clienteAjustaOLimite_ateOAprovado() {
        UUID cardId = goodPayer();

        assertThat(creditLimits.adjust(cardId, new BigDecimal("600.00")).availableLimit()).isEqualByComparingTo("600.00");
        assertThatThrownBy(() -> creditLimits.adjust(cardId, new BigDecimal("1000.01")))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("análise");
        assertThat(creditLimits.adjust(cardId, new BigDecimal("1000.00")).creditLimit()).isEqualByComparingTo("1000.00");
    }

    @Test
    void ofertaRecusada_eOfertaVencida() {
        UUID cardId = goodPayer();
        CreditLimits.ReviewOutput review = creditLimits.requestReview(cardId);

        // 31 dias depois, a oferta venceu
        today(LocalDate.of(2034, 5, 26));
        assertThat(creditLimits.offers(cardId)).singleElement().satisfies(o -> assertThat(o.status()).isEqualTo("EXPIRED"));
        assertThatThrownBy(() -> creditLimits.accept(cardId, review.offer().offerId()))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("venceu");
    }

    @Test
    void recusar_naoMudaOLimite() {
        UUID cardId = goodPayer();
        CreditLimits.ReviewOutput review = creditLimits.requestReview(cardId);

        assertThat(creditLimits.decline(cardId, review.offer().offerId()).status()).isEqualTo("DECLINED");
        assertThat(creditLimits.limits(cardId).approvedLimit()).isEqualByComparingTo("1000.00");
    }

    @Test
    void cartaoNovo_semHistorico_recebeOsMotivos() {
        UUID cardId = issueCard.execute(issueCardInput(new BigDecimal("1000.00"), 10, 20)).cardId();

        CreditLimits.ReviewOutput review = creditLimits.requestReview(cardId);

        assertThat(review.approved()).isFalse();
        assertThat(review.offer()).isNull();
        assertThat(review.reasons()).anySatisfy(r -> assertThat(r).contains("3 faturas vencidas"));
    }

    @Test
    void rodadaDoBanco_ofereceAQuemPassa() {
        UUID cardId = goodPayer();

        CreditLimits.RunOutput run = creditLimits.runReviews(LocalDate.of(2034, 4, 25));

        // a contagem prova que a oferta e o evento do aviso entraram na mesma transação (sem transação,
        // o outbox falha depois do INSERT e a oferta fica sem aviso — o bug que este teste deixou passar)
        assertThat(run.offersCreated()).isGreaterThanOrEqualTo(1);
        assertThat(creditLimits.offers(cardId)).singleElement().satisfies(o -> {
            assertThat(o.status()).isEqualTo("PENDING");
            assertThat(o.offeredLimit()).isEqualByComparingTo("1250.00");
        });
        // rodar de novo não cria outra (já há uma aberta)
        creditLimits.runReviews(LocalDate.of(2034, 4, 25));
        assertThat(creditLimits.offers(cardId)).hasSize(1);
    }
}
