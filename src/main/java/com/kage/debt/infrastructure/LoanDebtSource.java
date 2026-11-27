package com.kage.debt.infrastructure;

import com.kage.debt.domain.Debt;
import com.kage.debt.domain.DebtKind;
import com.kage.debt.domain.DebtSource;
import com.kage.loan.domain.entity.Loan;
import com.kage.loan.domain.repository.LoanRepository;
import com.kage.shared.domain.valueobject.Money;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Empréstimos e acordos ativos: o saldo das parcelas em aberto e quanto custa quitar hoje. */
public class LoanDebtSource implements DebtSource {

    private final LoanRepository loanRepository;

    public LoanDebtSource(LoanRepository loanRepository) {
        this.loanRepository = loanRepository;
    }

    @Override
    public List<Debt> debtsOf(UUID customerId, LocalDate today) {
        return loanRepository.findByCustomerId(customerId).stream()
                .filter(loan -> loan.getStatus() == Loan.Status.ACTIVE)
                .map(loan -> toDebt(loan, today))
                .toList();
    }

    private static Debt toDebt(Loan loan, LocalDate today) {
        List<Loan.Installment> pending = loan.pending();
        Money outstanding = pending.stream().map(Loan.Installment::amount).reduce(Money.ZERO, Money::add);
        Loan.Installment next = pending.get(0);
        int overdue = (int) pending.stream().filter(i -> today.isAfter(i.dueDate())).count();
        boolean agreement = loan.getOrigin() == Loan.Origin.RENEGOTIATION;
        return new Debt(agreement ? DebtKind.LOAN_AGREEMENT : DebtKind.LOAN, loan.getId(), null,
                (agreement ? "Acordo de renegociação" : "Empréstimo pessoal") + " (" + loan.getInstallments().size() + "x)",
                outstanding.amount(), loan.payoffCost(today).amount(), next.dueDate(), next.amount().amount(),
                pending.size(), loan.getMonthlyRate(), loan.getCetYearly(), overdue > 0, null, null, overdue);
    }
}
