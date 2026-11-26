package com.kage.loan;

import com.kage.AbstractIntegrationTest;
import com.kage.account.application.usecase.BlockAccount;
import com.kage.account.application.usecase.DepositAccount;
import com.kage.account.application.usecase.GetStatement;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.loan.application.usecase.PersonalLoans;
import com.kage.loan.application.usecase.RunLoanDebits;
import com.kage.shared.domain.DomainTime;
import com.kage.shared.domain.exception.BusinessRuleException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Empréstimo pessoal com Postgres real: oferta pela movimentação, contratação (dinheiro na conta),
 * arrependimento, débito automático com atraso, antecipação com desconto e quitação.
 * Relógio do domínio fixado em 2035.
 */
class PersonalLoanIntegrationTest extends AbstractIntegrationTest {

    @Autowired PersonalLoans personalLoans;
    @Autowired RunLoanDebits runLoanDebits;
    @Autowired DepositAccount depositAccount;
    @Autowired BlockAccount blockAccount;
    @Autowired GetStatement getStatement;
    @Autowired AccountRepository accountRepository;

    @AfterEach
    void resetClock() {
        DomainTime.reset();
    }

    private static void today(LocalDate date) {
        ZoneId zone = ZoneId.systemDefault();
        DomainTime.use(Clock.fixed(date.atTime(10, 0).atZone(zone).toInstant(), zone));
    }

    private BigDecimal balance(UUID accountId) {
        return accountRepository.findById(accountId).orElseThrow().getBalance().amount();
    }

    /** Conta com 6000 de entrada nos últimos 90 dias: renda de 2000, parcela de até 600. */
    private UUID accountWithIncome() {
        today(LocalDate.of(2035, 3, 1));
        UUID accountId = newAccountIdFor(newActiveCustomerId());
        depositAccount.execute(new DepositAccount.Input(accountId, new BigDecimal("6000.00")));
        return accountId;
    }

    @Test
    void oferta_simulacao_contratacao_eArrependimento() {
        UUID accountId = accountWithIncome();

        PersonalLoans.Offer offer = personalLoans.offer(accountId);
        assertThat(offer.monthlyIncome()).isEqualByComparingTo("2000.00");
        assertThat(offer.maxInstallment()).isEqualByComparingTo("600.00");
        assertThat(offer.maxAmount()).isPositive().isLessThan(new BigDecimal("50000"));
        assertThat(offer.hasActiveLoan()).isFalse();

        PersonalLoans.Simulation simulation = personalLoans.simulate(accountId, new BigDecimal("5000.00"), 12, 10);
        assertThat(simulation.released()).isEqualByComparingTo("5000.00");
        assertThat(simulation.financed()).isEqualByComparingTo(simulation.released().add(simulation.iof()));
        assertThat(simulation.iof()).isPositive();
        assertThat(simulation.cetMonthly()).isGreaterThan(new BigDecimal("0.0399"));
        assertThat(simulation.firstDueDate()).isEqualTo(LocalDate.of(2035, 4, 10));
        assertThat(simulation.installmentAmount()).isLessThanOrEqualTo(new BigDecimal("600.00"));
        // parcela que passa de 30% da renda
        assertThatThrownBy(() -> personalLoans.simulate(accountId, new BigDecimal("10000.00"), 12, 10))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("30%");

        PersonalLoans.Output loan = personalLoans.contract(accountId, new BigDecimal("5000.00"), 12, 10);
        assertThat(loan.status()).isEqualTo("ACTIVE");
        assertThat(loan.installments()).hasSize(12);
        assertThat(balance(accountId)).isEqualByComparingTo("11000.00"); // caiu na conta
        assertThat(getStatement.execute(new GetStatement.Input(accountId, LocalDate.of(2035, 3, 1), LocalDate.of(2035, 3, 1))).entries())
                .anySatisfy(e -> assertThat(e.type()).isEqualTo("LOAN_DISBURSEMENT"));
        assertThatThrownBy(() -> personalLoans.contract(accountId, new BigDecimal("1000.00"), 6, 10))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("ativo");

        // arrependimento no 7º dia: devolve só o que recebeu (sem juros nem IOF)
        today(LocalDate.of(2035, 3, 8));
        PersonalLoans.Payment withdrawal = personalLoans.withdraw(accountId, loan.loanId());
        assertThat(withdrawal.amount()).isEqualByComparingTo("5000.00");
        assertThat(withdrawal.status()).isEqualTo("CANCELLED");
        assertThat(balance(accountId)).isEqualByComparingTo("6000.00");
        assertThat(personalLoans.offer(accountId).hasActiveLoan()).isFalse();
    }

    @Test
    void arrependimentoDepoisDos7Dias_naoDa() {
        UUID accountId = accountWithIncome();
        PersonalLoans.Output loan = personalLoans.contract(accountId, new BigDecimal("1000.00"), 6, 10);

        today(LocalDate.of(2035, 3, 9));
        assertThatThrownBy(() -> personalLoans.withdraw(accountId, loan.loanId()))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("7 dias");
    }

    @Test
    void debitoAutomaticoComAtraso_antecipacaoComDesconto_eQuitacao() {
        UUID accountId = accountWithIncome();
        PersonalLoans.Output loan = personalLoans.contract(accountId, new BigDecimal("3000.00"), 6, 10);
        BigDecimal installment = loan.installments().get(0).amount();
        LocalDate firstDue = loan.installments().get(0).dueDate();
        assertThat(firstDue).isEqualTo(LocalDate.of(2035, 4, 10));

        // o job só roda 3 dias depois do vencimento: cobra com multa e mora
        // (o job é global: pode pagar também empréstimos de outros testes no mesmo banco)
        today(firstDue.plusDays(3));
        runLoanDebits.execute(firstDue.plusDays(3));
        PersonalLoans.Output afterJob = personalLoans.get(accountId, loan.loanId());
        assertThat(afterJob.paidInstallments()).isEqualTo(1); // só a vencida; a 2ª ainda não venceu
        PersonalLoans.InstallmentView first = afterJob.installments().get(0);
        assertThat(first.status()).isEqualTo("PAID");
        // 2% + 1% a.m. × 3/30
        BigDecimal expectedLate = installment.multiply(new BigDecimal("0.02")).setScale(2, java.math.RoundingMode.HALF_UP)
                .add(installment.multiply(new BigDecimal("0.01")).multiply(BigDecimal.valueOf(3))
                        .divide(BigDecimal.valueOf(30), 2, java.math.RoundingMode.HALF_UP));
        assertThat(first.lateCharges()).isEqualByComparingTo(expectedLate);
        assertThat(first.paidAmount()).isEqualByComparingTo(installment.add(expectedLate));
        // rodar de novo no mesmo dia não cobra outra vez
        BigDecimal balanceAfterJob = balance(accountId);
        runLoanDebits.execute(firstDue.plusDays(3));
        assertThat(personalLoans.get(accountId, loan.loanId()).paidInstallments()).isEqualTo(1);
        assertThat(balance(accountId)).isEqualByComparingTo(balanceAfterJob);

        // a 2ª, paga 20 dias antes do vencimento: com desconto dos juros
        today(firstDue.plusMonths(1).minusDays(20));
        PersonalLoans.Payment early = personalLoans.payNext(accountId, loan.loanId());
        assertThat(early.installment()).isEqualTo(2);
        assertThat(early.discount()).isPositive();
        assertThat(early.amount()).isLessThan(installment);

        // quitação: as 4 restantes a valor presente
        PersonalLoans.Payment quote = personalLoans.payoffQuote(accountId, loan.loanId());
        assertThat(quote.amount()).isLessThan(installment.multiply(BigDecimal.valueOf(4)));
        assertThat(quote.discount()).isPositive();
        BigDecimal before = balance(accountId);
        PersonalLoans.Payment payoff = personalLoans.payOff(accountId, loan.loanId());
        assertThat(payoff.status()).isEqualTo("PAID");
        assertThat(payoff.amount()).isEqualByComparingTo(quote.amount());
        assertThat(balance(accountId)).isEqualByComparingTo(before.subtract(quote.amount()));

        PersonalLoans.Output paid = personalLoans.get(accountId, loan.loanId());
        assertThat(paid.status()).isEqualTo("PAID");
        assertThat(paid.outstanding()).isEqualByComparingTo("0");
        assertThat(paid.paidInstallments()).isEqualTo(6);
        assertThatThrownBy(() -> personalLoans.payNext(accountId, loan.loanId()))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("quitado");
    }

    @Test
    void contaBloqueada_parcelaFicaParaODiaSeguinte() {
        UUID accountId = accountWithIncome();
        PersonalLoans.Output loan = personalLoans.contract(accountId, new BigDecimal("1000.00"), 3, 10);
        blockAccount.execute(new BlockAccount.Input(accountId));

        LocalDate due = loan.installments().get(0).dueDate();
        today(due);
        RunLoanDebits.Output run = runLoanDebits.execute(due);

        assertThat(run.failures()).anySatisfy(f -> {
            assertThat(f.loanId()).isEqualTo(loan.loanId());
            assertThat(f.reason()).contains("não está ativa");
        });
        assertThat(personalLoans.get(accountId, loan.loanId()).installments().get(0).status()).isEqualTo("PENDING");
    }

    @Test
    void semMovimentacao_semOferta() {
        today(LocalDate.of(2035, 3, 1));
        UUID accountId = newAccountIdFor(newActiveCustomerId());

        assertThat(personalLoans.offer(accountId).maxAmount()).isEqualByComparingTo("0");
        assertThatThrownBy(() -> personalLoans.contract(accountId, new BigDecimal("500.00"), 6, 10))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("30%");
    }
}
