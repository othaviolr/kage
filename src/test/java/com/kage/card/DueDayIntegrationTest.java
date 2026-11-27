package com.kage.card;

import com.kage.AbstractIntegrationTest;
import com.kage.card.application.usecase.CloseInvoice;
import com.kage.card.application.usecase.DueDays;
import com.kage.card.application.usecase.GetCard;
import com.kage.card.application.usecase.GetInvoice;
import com.kage.card.application.usecase.IssueCard;
import com.kage.card.application.usecase.RegisterPurchase;
import com.kage.card.application.usecase.RunBillingCycle;
import com.kage.notification.application.usecase.ListNotifications;
import com.kage.shared.domain.DomainTime;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.ValidationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Troca do dia de vencimento com Postgres real (e o job de cobrança fechando pelas datas novas). */
class DueDayIntegrationTest extends AbstractIntegrationTest {

    @Autowired IssueCard issueCard;
    @Autowired DueDays dueDays;
    @Autowired RegisterPurchase registerPurchase;
    @Autowired GetInvoice getInvoice;
    @Autowired GetCard getCard;
    @Autowired CloseInvoice closeInvoice;
    @Autowired RunBillingCycle runBillingCycle;
    @Autowired ListNotifications listNotifications;

    @AfterEach
    void resetClock() {
        DomainTime.reset();
    }

    private static void today(LocalDate date) {
        ZoneId zone = ZoneId.systemDefault();
        DomainTime.use(Clock.fixed(date.atTime(10, 0).atZone(zone).toInstant(), zone));
    }

    private record Setup(UUID customerId, UUID cardId) {}

    private Setup card(int closingDay, int dueDay) {
        today(LocalDate.of(2034, 1, 5));
        IssueCard.Input input = issueCardInput(new BigDecimal("5000.00"), closingDay, dueDay);
        return new Setup(input.customerId(), issueCard.execute(input).cardId());
    }

    private String buy(UUID cardId, String amount, int installments, LocalDateTime at) {
        return registerPurchase.execute(new RegisterPurchase.Input(cardId, UUID.randomUUID(), "Compra",
                new BigDecimal(amount), at, installments)).referenceMonth();
    }

    private GetInvoice.Output invoice(UUID cardId, String month) {
        return getInvoice.execute(new GetInvoice.Input(cardId, month));
    }

    @Test
    void fechamentoMaisTarde_aSeguinteEAMaisLonga_eOJobFechaNaDataNova() {
        Setup s = card(10, 20);
        buy(s.cardId(), "300.00", 3, LocalDateTime.of(2034, 1, 5, 10, 0)); // parcelas em jan, fev e mar

        DueDays.Change change = dueDays.change(s.cardId(), 5);

        assertThat(change.closingDay()).isEqualTo(25);
        assertThat(change.previousDueDay()).isEqualTo(20);
        assertThat(change.nextChangeAllowedOn()).isEqualTo(LocalDate.of(2034, 7, 4));
        assertThat(change.transition()).satisfies(t -> {
            assertThat(t.referenceMonth()).isEqualTo("2034-02");
            assertThat(t.periodStart()).isEqualTo(LocalDate.of(2034, 1, 11));
            assertThat(t.closingDate()).isEqualTo(LocalDate.of(2034, 2, 25));
            assertThat(t.dueDate()).isEqualTo(LocalDate.of(2034, 3, 5));
            assertThat(t.purchaseDays()).isEqualTo(46);
            assertThat(t.note()).isEqualTo("A fatura de 02/2034 é a de transição: junta as compras de 11/01 a 25/02 "
                    + "(46 dias, mais longa que o normal) e vence em 05/03. Depois, todas fecham dia 25 e vencem dia 5.");
        });
        GetCard.Output cardNow = getCard.execute(new GetCard.Input(s.cardId()));
        assertThat(cardNow.dueDay()).isEqualTo(5);
        assertThat(cardNow.closingDay()).isEqualTo(25);

        // a atual não muda; as futuras que já existiam vão para as datas novas
        assertThat(invoice(s.cardId(), "2034-01").closingDate()).isEqualTo(LocalDate.of(2034, 1, 10));
        assertThat(invoice(s.cardId(), "2034-01").dueDate()).isEqualTo(LocalDate.of(2034, 1, 20));
        assertThat(invoice(s.cardId(), "2034-02").closingDate()).isEqualTo(LocalDate.of(2034, 2, 25));
        assertThat(invoice(s.cardId(), "2034-03").dueDate()).isEqualTo(LocalDate.of(2034, 4, 5));

        // compras: até o dia 10 ainda é janeiro (o fechamento antigo); depois, fevereiro
        assertThat(buy(s.cardId(), "50.00", 1, LocalDateTime.of(2034, 1, 8, 10, 0))).isEqualTo("2034-01");
        assertThat(buy(s.cardId(), "70.00", 1, LocalDateTime.of(2034, 1, 15, 10, 0))).isEqualTo("2034-02");

        // o job fecha janeiro no dia 11 e só fecha fevereiro depois do dia 25 (o banco é compartilhado com
        // outros testes: confere as faturas deste cartão, não a contagem da rodada)
        runBillingCycle.execute(LocalDate.of(2034, 1, 11));
        assertThat(invoice(s.cardId(), "2034-01").status()).isEqualTo("CLOSED");
        runBillingCycle.execute(LocalDate.of(2034, 2, 11));
        assertThat(invoice(s.cardId(), "2034-02").status()).isEqualTo("OPEN");
        runBillingCycle.execute(LocalDate.of(2034, 2, 26));
        assertThat(invoice(s.cardId(), "2034-02").status()).isEqualTo("CLOSED");

        // uma troca a cada 180 dias
        today(LocalDate.of(2034, 3, 1));
        DueDays.Status status = dueDays.status(s.cardId());
        assertThat(status.canChange()).isFalse();
        assertThat(status.nextChangeAllowedOn()).isEqualTo(LocalDate.of(2034, 7, 4));
        assertThat(status.blockedReason()).isEqualTo("O vencimento foi trocado há menos de 180 dias: a próxima troca "
                + "pode ser a partir de 04/07/2034");
        assertThatThrownBy(() -> dueDays.change(s.cardId(), 15)).isInstanceOf(BusinessRuleException.class);

        // o aviso
        awaitUntil(() -> listNotifications.execute(new ListNotifications.Input(s.customerId(), false)).notifications()
                .stream().anyMatch(n -> n.type().equals("CARD_DUE_DAY_CHANGED")), Duration.ofSeconds(15));
        assertThat(listNotifications.execute(new ListNotifications.Input(s.customerId(), false)).notifications().stream()
                .filter(n -> n.type().equals("CARD_DUE_DAY_CHANGED")).findFirst().orElseThrow().message())
                .isEqualTo("Suas faturas passam a vencer todo dia 5 e a fechar no dia 25. A de 02/2034 é a de transição: "
                        + "vence em 05/03, com 46 dias de compras.");
    }

    @Test
    void fechamentoMaisCedo_aSeguinteEAMaisCurta_eACompraAindaCaiNaAtual() {
        Setup s = card(25, 5); // jan fecha 25/01 e vence 05/02
        buy(s.cardId(), "100.00", 1, LocalDateTime.of(2034, 1, 5, 10, 0));

        DueDays.Change change = dueDays.change(s.cardId(), 15); // fecha dia 5

        assertThat(change.transition().referenceMonth()).isEqualTo("2034-02");
        assertThat(change.transition().purchaseDays()).isEqualTo(11); // 26/01 a 05/02
        assertThat(change.transition().note()).contains("(11 dias, mais curta que o normal)").contains("vence em 15/02");

        // dia 20 já passou do fechamento novo (5), mas janeiro ainda fecha no dia 25
        assertThat(buy(s.cardId(), "50.00", 1, LocalDateTime.of(2034, 1, 20, 10, 0))).isEqualTo("2034-01");
        assertThat(buy(s.cardId(), "30.00", 1, LocalDateTime.of(2034, 1, 28, 10, 0))).isEqualTo("2034-02");
        assertThat(invoice(s.cardId(), "2034-02").closingDate()).isEqualTo(LocalDate.of(2034, 2, 5));
        assertThat(invoice(s.cardId(), "2034-02").dueDate()).isEqualTo(LocalDate.of(2034, 2, 15));
    }

    @Test
    void recusas() {
        Setup s = card(10, 20);
        assertThatThrownBy(() -> dueDays.change(s.cardId(), 20)).isInstanceOf(BusinessRuleException.class)
                .hasMessage("A fatura já vence no dia 20");
        assertThatThrownBy(() -> dueDays.change(s.cardId(), 29)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> dueDays.change(s.cardId(), null)).isInstanceOf(ValidationException.class);

        DueDays.Status status = dueDays.status(s.cardId());
        assertThat(status.canChange()).isTrue();
        assertThat(status.options()).hasSize(27).doesNotContain(new DueDays.Option(20, 10))
                .contains(new DueDays.Option(5, 25), new DueDays.Option(10, 28));

        // fatura vencida em aberto: paga antes, troca depois
        buy(s.cardId(), "100.00", 1, LocalDateTime.of(2034, 1, 5, 10, 0));
        closeInvoice.execute(new CloseInvoice.Input(s.cardId(), "2034-01"));
        today(LocalDate.of(2034, 1, 21));
        assertThat(dueDays.status(s.cardId()).blockedReason())
                .isEqualTo("Há fatura vencida em aberto: pague antes de trocar o vencimento");
        assertThatThrownBy(() -> dueDays.change(s.cardId(), 5)).isInstanceOf(BusinessRuleException.class);
    }
}
