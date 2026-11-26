package com.kage.loan;

import com.kage.AbstractIntegrationTest;
import com.kage.account.application.usecase.BlockAccount;
import com.kage.account.application.usecase.DepositAccount;
import com.kage.account.application.usecase.UnblockAccount;
import com.kage.loan.application.usecase.PersonalLoans;
import com.kage.loan.application.usecase.RunLoanDebits;
import com.kage.notification.application.usecase.ListNotifications;
import com.kage.notification.application.usecase.NotificationPreferences;
import com.kage.shared.domain.DomainTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Avisos do empréstimo com o fluxo real (outbox → RabbitMQ → caixa de entrada). */
class LoanNotificationIntegrationTest extends AbstractIntegrationTest {

    @Autowired PersonalLoans personalLoans;
    @Autowired RunLoanDebits runLoanDebits;
    @Autowired DepositAccount depositAccount;
    @Autowired BlockAccount blockAccount;
    @Autowired UnblockAccount unblockAccount;
    @Autowired ListNotifications listNotifications;
    @Autowired NotificationPreferences notificationPreferences;

    @AfterEach
    void resetClock() {
        DomainTime.reset();
    }

    private static void today(LocalDate date) {
        ZoneId zone = ZoneId.systemDefault();
        DomainTime.use(Clock.fixed(date.atTime(10, 0).atZone(zone).toInstant(), zone));
    }

    private record Setup(UUID customerId, UUID accountId) {}

    private Setup customer() {
        today(LocalDate.of(2035, 3, 1));
        UUID customerId = newActiveCustomerId();
        UUID accountId = newAccountIdFor(customerId);
        depositAccount.execute(new DepositAccount.Input(accountId, new BigDecimal("6000.00")));
        return new Setup(customerId, accountId);
    }

    private List<ListNotifications.Item> inbox(UUID customerId, String type) {
        return listNotifications.execute(new ListNotifications.Input(customerId, false)).notifications().stream()
                .filter(n -> n.type().equals(type)).toList();
    }

    private void await(UUID customerId, String type, int count) {
        awaitUntil(() -> inbox(customerId, type).size() >= count, Duration.ofSeconds(15));
    }

    @Test
    void contratacao_lembrete_pagamento_eQuitacao() {
        Setup s = customer();
        PersonalLoans.Output loan = personalLoans.contract(s.accountId(), new BigDecimal("3000.00"), 6, 10);
        await(s.customerId(), "LOAN_CONTRACTED", 1);
        assertThat(inbox(s.customerId(), "LOAN_CONTRACTED").get(0).message())
                .startsWith("R$ 3.000,00 já estão na sua conta. São 6 parcelas de")
                .contains("a primeira em 10/04")
                .contains("desistir em até 7 dias");

        // 3 dias antes do vencimento: lembrete — e só um, mesmo rodando duas vezes
        LocalDate firstDue = loan.installments().get(0).dueDate();
        today(firstDue.minusDays(3));
        runLoanDebits.execute(firstDue.minusDays(3));
        runLoanDebits.execute(firstDue.minusDays(3));
        await(s.customerId(), "LOAN_INSTALLMENT_DUE_SOON", 1);
        sleep(Duration.ofMillis(500));
        assertThat(inbox(s.customerId(), "LOAN_INSTALLMENT_DUE_SOON")).singleElement()
                .satisfies(n -> assertThat(n.message()).contains("A parcela 1/6").contains("vence em 10/04"));

        // no vencimento, o débito automático paga
        today(firstDue);
        runLoanDebits.execute(firstDue);
        await(s.customerId(), "LOAN_INSTALLMENT_PAID", 1);
        assertThat(inbox(s.customerId(), "LOAN_INSTALLMENT_PAID").get(0).message())
                .startsWith("Parcela 1/6 do empréstimo paga").contains("Faltam 5 parcelas.");

        // quitação
        personalLoans.payOff(s.accountId(), loan.loanId());
        await(s.customerId(), "LOAN_PAID_OFF", 1);
        assertThat(inbox(s.customerId(), "LOAN_PAID_OFF").get(0).message())
                .startsWith("Você quitou o empréstimo").contains("de desconto nos juros");
    }

    @Test
    void debitoQueNaoPassa_avisaUmaVez_eMesmoComACategoriaDesligada() {
        Setup s = customer();
        notificationPreferences.set(s.customerId(), "LOANS", false);
        PersonalLoans.Output loan = personalLoans.contract(s.accountId(), new BigDecimal("1000.00"), 3, 10);
        blockAccount.execute(new BlockAccount.Input(s.accountId()));

        LocalDate due = loan.installments().get(0).dueDate();
        today(due);
        runLoanDebits.execute(due);
        today(due.plusDays(1));
        runLoanDebits.execute(due.plusDays(1)); // tenta de novo no dia seguinte, sem avisar de novo

        await(s.customerId(), "LOAN_INSTALLMENT_FAILED", 1);
        sleep(Duration.ofMillis(500));
        assertThat(inbox(s.customerId(), "LOAN_INSTALLMENT_FAILED")).singleElement().satisfies(n -> {
            assertThat(n.pushed()).isTrue(); // crítico: a categoria desligada não segura
            assertThat(n.message()).contains("parcela 1/3").contains("não está ativa").contains("tentamos de novo todo dia");
        });
        // o contratado (não crítico) ficou só no app
        await(s.customerId(), "LOAN_CONTRACTED", 1);
        assertThat(inbox(s.customerId(), "LOAN_CONTRACTED").get(0).pushed()).isFalse();
    }

    @Test
    void arrependimento_avisa() {
        Setup s = customer();
        PersonalLoans.Output loan = personalLoans.contract(s.accountId(), new BigDecimal("1000.00"), 3, 10);

        personalLoans.withdraw(s.accountId(), loan.loanId());

        await(s.customerId(), "LOAN_WITHDRAWN", 1);
        assertThat(inbox(s.customerId(), "LOAN_WITHDRAWN").get(0).message())
                .isEqualTo("Cancelamos o empréstimo: R$ 1.000,00 voltaram para o Kage, sem juros nem IOF.");
    }
}
