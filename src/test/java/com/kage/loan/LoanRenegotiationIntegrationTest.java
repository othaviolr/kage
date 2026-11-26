package com.kage.loan;

import com.kage.AbstractIntegrationTest;
import com.kage.account.application.usecase.DepositAccount;
import com.kage.account.application.usecase.GetStatement;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.loan.application.usecase.PersonalLoans;
import com.kage.loan.application.usecase.RunLoanDebits;
import com.kage.notification.application.usecase.ListNotifications;
import com.kage.shared.domain.DomainTime;
import com.kage.shared.domain.exception.BusinessRuleException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Renegociação com Postgres real: 3000 em 6x contratado em 01/03/2035 (vencimento dia 10); as parcelas de
 * abril e maio não são pagas, e em 20/05 o cliente fecha um acordo.
 */
class LoanRenegotiationIntegrationTest extends AbstractIntegrationTest {

    @Autowired PersonalLoans personalLoans;
    @Autowired RunLoanDebits runLoanDebits;
    @Autowired DepositAccount depositAccount;
    @Autowired AccountRepository accountRepository;
    @Autowired GetStatement getStatement;
    @Autowired ListNotifications listNotifications;
    @Autowired JdbcTemplate jdbcTemplate;

    @AfterEach
    void resetClock() {
        DomainTime.reset();
    }

    private static void today(LocalDate date) {
        ZoneId zone = ZoneId.systemDefault();
        DomainTime.use(Clock.fixed(date.atTime(10, 0).atZone(zone).toInstant(), zone));
    }

    private record Setup(UUID customerId, UUID accountId, PersonalLoans.Output loan) {}

    private Setup lateLoan() {
        today(LocalDate.of(2035, 3, 1));
        UUID customerId = newActiveCustomerId();
        UUID accountId = newAccountIdFor(customerId);
        depositAccount.execute(new DepositAccount.Input(accountId, new BigDecimal("6000.00")));
        PersonalLoans.Output loan = personalLoans.contract(accountId, new BigDecimal("3000.00"), 6, 10);
        today(LocalDate.of(2035, 5, 20)); // abril e maio vencidos, sem pagar
        return new Setup(customerId, accountId, loan);
    }

    private BigDecimal balance(UUID accountId) {
        return accountRepository.findById(accountId).orElseThrow().getBalance().amount();
    }

    @Test
    void proposta_eAcordo_substituemOEmprestimoAtrasado() {
        Setup s = lateLoan();
        UUID loanId = s.loan().loanId();
        BigDecimal payoff = personalLoans.payoffQuote(s.accountId(), loanId).amount();

        PersonalLoans.Renegotiation quote = personalLoans.renegotiationQuote(s.accountId(), loanId, 12, 15,
                new BigDecimal("300.00"));
        // a dívida de hoje é a da quitação: vencidas com multa e mora, futuras a valor presente
        assertThat(quote.debt()).isEqualByComparingTo(payoff);
        assertThat(quote.overdue()).isEqualTo(2);
        assertThat(quote.lateCharges()).isPositive();
        assertThat(quote.renegotiated()).isEqualByComparingTo(payoff.subtract(new BigDecimal("300.00")));
        assertThat(quote.agreement().released()).isEqualByComparingTo(quote.renegotiated());
        assertThat(quote.agreement().monthlyRate()).isEqualByComparingTo("0.0399"); // a taxa do contrato
        assertThat(quote.agreement().installments()).isEqualTo(12);
        // 1ª parcela a pelo menos 30 dias: 15/06 está a 26, então vai para 15/07
        assertThat(quote.agreement().firstDueDate()).isEqualTo(LocalDate.of(2035, 7, 15));
        // CET informado (pelas datas reais: a 1ª parcela a 56 dias com juros de um mês deixa o CET abaixo da taxa)
        assertThat(quote.agreement().cetMonthly()).isPositive();

        BigDecimal before = balance(s.accountId());
        PersonalLoans.Output agreement = personalLoans.renegotiate(s.accountId(), loanId, 12, 15, new BigDecimal("300.00"));

        assertThat(agreement.status()).isEqualTo("ACTIVE");
        assertThat(agreement.origin()).isEqualTo("RENEGOTIATION");
        assertThat(agreement.renegotiatedFrom()).isEqualTo(loanId);
        assertThat(agreement.downPayment()).isEqualByComparingTo("300.00");
        assertThat(agreement.released()).isEqualByComparingTo(quote.renegotiated());
        assertThat(agreement.installments()).hasSize(12)
                .first().satisfies(i -> assertThat(i.amount()).isEqualByComparingTo(quote.agreement().installmentAmount()));
        // só a entrada sai da conta: nada entra
        assertThat(balance(s.accountId())).isEqualByComparingTo(before.subtract(new BigDecimal("300.00")));

        PersonalLoans.Output original = personalLoans.get(s.accountId(), loanId);
        assertThat(original.status()).isEqualTo("RENEGOTIATED");
        assertThat(original.outstanding()).isEqualByComparingTo("0");
        assertThat(original.installments()).filteredOn(i -> !i.status().equals("PAID"))
                .allSatisfy(i -> assertThat(i.status()).isEqualTo("RENEGOTIATED"));

        // o acordo é o empréstimo ativo da conta; o original não aceita mais nada
        assertThat(personalLoans.offer(s.accountId()).hasActiveLoan()).isTrue();
        assertThatThrownBy(() -> personalLoans.payNext(s.accountId(), loanId))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("renegociado");
        assertThatThrownBy(() -> personalLoans.renegotiate(s.accountId(), loanId, 12, 15, null))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("renegociado");
        // acordo não tem arrependimento (não houve dinheiro novo)
        assertThatThrownBy(() -> personalLoans.withdraw(s.accountId(), agreement.loanId()))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("arrependimento");
        // e o acordo em dia não se renegocia
        assertThatThrownBy(() -> personalLoans.renegotiationQuote(s.accountId(), agreement.loanId(), 12, 15, null))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("Não há parcela em atraso");

        // o débito automático cobra o acordo, não o original
        today(LocalDate.of(2035, 7, 15));
        runLoanDebits.execute(LocalDate.of(2035, 7, 15));
        assertThat(personalLoans.get(s.accountId(), agreement.loanId()).paidInstallments()).isEqualTo(1);
        assertThat(personalLoans.get(s.accountId(), loanId).paidInstallments()).isZero();

        // aviso
        awaitUntil(() -> inbox(s.customerId()).size() >= 1, Duration.ofSeconds(15));
        assertThat(inbox(s.customerId()).get(0).message())
                .startsWith("Sua dívida de R$ ")
                .contains("foi renegociada: entrada de R$ 300,00 e 12 parcelas de R$ ")
                .contains("a 1ª em 15/07/2035");
    }

    private List<ListNotifications.Item> inbox(UUID customerId) {
        return listNotifications.execute(new ListNotifications.Input(customerId, false)).notifications().stream()
                .filter(n -> n.type().equals("LOAN_RENEGOTIATED")).toList();
    }

    @Test
    void emDia_naoRenegocia() {
        today(LocalDate.of(2035, 3, 1));
        UUID accountId = newAccountIdFor(newActiveCustomerId());
        depositAccount.execute(new DepositAccount.Input(accountId, new BigDecimal("6000.00")));
        PersonalLoans.Output loan = personalLoans.contract(accountId, new BigDecimal("3000.00"), 6, 10);

        today(LocalDate.of(2035, 4, 10)); // vence hoje: ainda não está atrasada
        assertThatThrownBy(() -> personalLoans.renegotiationQuote(accountId, loan.loanId(), 6, 10, null))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("Não há parcela em atraso");
    }

    @Test
    void entradaQueQuita_ouSemSaldoParaAEntrada_naoFechaNada() {
        Setup s = lateLoan();
        UUID loanId = s.loan().loanId();
        BigDecimal debt = personalLoans.payoffQuote(s.accountId(), loanId).amount();

        assertThatThrownBy(() -> personalLoans.renegotiationQuote(s.accountId(), loanId, 6, 10, debt))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("use a quitação");

        // a conta só tem 100: a entrada de 500 não passa, e nada muda (nem o original, nem acordo gravado)
        jdbcTemplate.update("UPDATE accounts SET balance = 100, available_balance = 100 WHERE id = ?", s.accountId());
        assertThatThrownBy(() -> personalLoans.renegotiate(s.accountId(), loanId, 6, 10, new BigDecimal("500.00")))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("Saldo");
        assertThat(personalLoans.get(s.accountId(), loanId).status()).isEqualTo("ACTIVE");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM loans WHERE renegotiated_from = ?", Integer.class,
                loanId)).isZero();
    }
}
