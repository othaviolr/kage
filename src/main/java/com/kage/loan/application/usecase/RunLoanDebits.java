package com.kage.loan.application.usecase;

import com.kage.loan.domain.entity.Loan;
import com.kage.loan.domain.repository.LoanRepository;
import com.kage.loan.domain.service.LoanAccountGateway;
import com.kage.shared.domain.DomainTime;
import com.kage.shared.domain.exception.DomainException;
import com.kage.shared.domain.valueobject.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Débito automático das parcelas: no dia, cada empréstimo com parcela vencida em aberto tenta pagá-la
 * com o saldo da conta — em atraso, com multa e mora. Sem saldo, ela fica para o dia seguinte. Paga
 * em ordem: a mais antiga primeiro, e para na primeira que não passa.
 *
 * Cada empréstimo na própria transação: um sem saldo não desfaz os outros. Idempotente: parcela paga
 * não é cobrada de novo.
 *
 * Avisos: lembrete {@link #REMINDER_DAYS} dias antes do vencimento e aviso de débito que não passou —
 * cada um uma vez só por parcela (marcados no banco), embora o débito seja tentado todo dia.
 */
public class RunLoanDebits {

    private static final Logger logger = LoggerFactory.getLogger(RunLoanDebits.class);

    public record Failure(UUID loanId, int installment, String reason) {}
    public record Output(LocalDate date, int installmentsPaid, List<Failure> failures, int remindersSent) {}

    static final int REMINDER_DAYS = 3;

    private final LoanRepository loanRepository;
    private final LoanAccountGateway accounts;
    private final TransactionTemplate transactions;
    private final LoanEventPublisher events;

    public RunLoanDebits(LoanRepository loanRepository, LoanAccountGateway accounts, TransactionTemplate transactions,
                         LoanEventPublisher events) {
        this.loanRepository = loanRepository;
        this.accounts = accounts;
        this.transactions = transactions;
        this.events = events;
    }

    public Output execute(LocalDate date) {
        int reminders = remind(date.plusDays(REMINDER_DAYS));
        int paid = 0;
        List<Failure> failures = new ArrayList<>();
        for (UUID loanId : loanRepository.activeWithDueUntil(date)) {
            while (true) {
                Loan loan = loanRepository.findById(loanId).orElseThrow();
                if (!loan.hasDueOn(date)) break;
                int number = loan.nextPending().orElseThrow().number();
                try {
                    transactions.executeWithoutResult(tx -> {
                        Money amount = loan.payNext(date);
                        loanRepository.update(loan);
                        accounts.collect(loan.getAccountId(), amount, loan.getId());
                        events.publishInstallmentPaid(loan, loan.getInstallments().get(number - 1));
                    });
                    paid++;
                } catch (DomainException | OptimisticLockingFailureException e) {
                    logger.warn("Parcela {} do empréstimo {} fica para amanhã: {}", number, loanId, e.getMessage());
                    failures.add(new Failure(loanId, number, e.getMessage()));
                    notifyFailure(loanId, number, date, e.getMessage());
                    break;
                }
            }
        }
        if (paid > 0 || !failures.isEmpty()) {
            logger.info("Débito de empréstimos de {}: {} parcela(s) paga(s), {} sem saldo", date, paid, failures.size());
        }
        return new Output(date, paid, failures, reminders);
    }

    /** Lembrete das parcelas que vencem em {@code dueDate} — uma vez por parcela. */
    private int remind(LocalDate dueDate) {
        int sent = 0;
        for (LoanRepository.InstallmentRef ref : loanRepository.notRemindedDueOn(dueDate)) {
            Boolean marked = transactions.execute(tx -> {
                if (!loanRepository.markReminded(ref.loanId(), ref.number(), DomainTime.now())) return false;
                Loan loan = loanRepository.findById(ref.loanId()).orElseThrow();
                events.publishInstallmentDueSoon(loan, loan.getInstallments().get(ref.number() - 1));
                return true;
            });
            if (Boolean.TRUE.equals(marked)) sent++;
        }
        return sent;
    }

    /** O débito não passou: avisa o cliente na primeira vez (a transação do débito já foi desfeita). */
    private void notifyFailure(UUID loanId, int number, LocalDate date, String reason) {
        try {
            transactions.executeWithoutResult(tx -> {
                if (!loanRepository.markFailureNotified(loanId, number, DomainTime.now())) return;
                Loan loan = loanRepository.findById(loanId).orElseThrow();
                Loan.Installment installment = loan.getInstallments().get(number - 1);
                events.publishInstallmentFailed(loan, installment, installment.costOn(date, loan.getMonthlyRate()), reason);
            });
        } catch (RuntimeException e) {
            logger.warn("Aviso de débito que não passou (empréstimo {}, parcela {}) fica para amanhã: {}", loanId, number,
                    e.getMessage());
        }
    }
}
