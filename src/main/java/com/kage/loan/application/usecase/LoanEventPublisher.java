package com.kage.loan.application.usecase;

import com.kage.loan.domain.entity.Loan;
import com.kage.shared.domain.valueobject.Money;

/** Porta de saída dos eventos do empréstimo. A implementação grava no outbox. */
public interface LoanEventPublisher {

    /** Chamar dentro da transação da contratação. */
    void publishContracted(Loan loan);

    /** Parcela paga (débito automático, pagamento manual). Chamar dentro da transação do pagamento. */
    void publishInstallmentPaid(Loan loan, Loan.Installment installment);

    /**
     * O débito automático da parcela não passou. Abre transação própria se não houver uma: a do
     * pagamento já sofreu rollback quando a falha aparece.
     */
    void publishInstallmentFailed(Loan loan, Loan.Installment installment, Money due, String reason);

    /** Quitação antecipada. Chamar dentro da transação da quitação. */
    void publishPaidOff(Loan loan, Money paid, Money discount);

    /** Arrependimento. Chamar dentro da transação do cancelamento. */
    void publishWithdrawn(Loan loan, Money returned);

    /** Lembrete: a parcela vence em poucos dias. Chamar dentro da transação que marca o lembrete. */
    void publishInstallmentDueSoon(Loan loan, Loan.Installment installment);
}
