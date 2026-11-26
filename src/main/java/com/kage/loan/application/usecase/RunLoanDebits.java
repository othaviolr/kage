package com.kage.loan.application.usecase;

import com.kage.loan.domain.entity.Loan;
import com.kage.loan.domain.repository.LoanRepository;
import com.kage.loan.domain.service.LoanAccountGateway;
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
 */
public class RunLoanDebits {

    private static final Logger logger = LoggerFactory.getLogger(RunLoanDebits.class);

    public record Failure(UUID loanId, int installment, String reason) {}
    public record Output(LocalDate date, int installmentsPaid, List<Failure> failures) {}

    private final LoanRepository loanRepository;
    private final LoanAccountGateway accounts;
    private final TransactionTemplate transactions;

    public RunLoanDebits(LoanRepository loanRepository, LoanAccountGateway accounts, TransactionTemplate transactions) {
        this.loanRepository = loanRepository;
        this.accounts = accounts;
        this.transactions = transactions;
    }

    public Output execute(LocalDate date) {
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
                    });
                    paid++;
                } catch (DomainException | OptimisticLockingFailureException e) {
                    logger.warn("Parcela {} do empréstimo {} fica para amanhã: {}", number, loanId, e.getMessage());
                    failures.add(new Failure(loanId, number, e.getMessage()));
                    break;
                }
            }
        }
        if (paid > 0 || !failures.isEmpty()) {
            logger.info("Débito de empréstimos de {}: {} parcela(s) paga(s), {} sem saldo", date, paid, failures.size());
        }
        return new Output(date, paid, failures);
    }
}
