package com.kage.loan.application.usecase;

import com.kage.loan.domain.entity.Loan;
import com.kage.loan.domain.repository.LoanRepository;
import com.kage.loan.domain.service.LoanAccountGateway;
import com.kage.loan.domain.service.LoanPolicy;
import com.kage.shared.domain.DomainTime;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.ConflictException;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.exception.ValidationException;
import com.kage.shared.domain.valueobject.Money;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Empréstimo pessoal da conta: oferta (pela movimentação), simulação, contratação (o dinheiro cai na
 * conta), consulta, pagamento de parcela (antecipada com desconto, atrasada com encargos), quitação e
 * arrependimento em 7 dias.
 */
public class PersonalLoans {

    /** Janela da renda estimada: entradas dos últimos 90 dias ÷ 3. */
    static final int INCOME_DAYS = 90;
    static final int DEFAULT_DUE_DAY = 10;

    public record Offer(BigDecimal monthlyIncome, BigDecimal maxInstallment, BigDecimal maxAmount, int maxInstallments,
                       BigDecimal monthlyRate, boolean hasActiveLoan) {}

    public record InstallmentView(int number, LocalDate dueDate, BigDecimal amount, BigDecimal principal,
                                  BigDecimal interest, String status, LocalDateTime paidAt, BigDecimal paidAmount,
                                  BigDecimal lateCharges, BigDecimal discount) {}

    public record Simulation(BigDecimal released, BigDecimal iof, BigDecimal financed, int installments,
                             BigDecimal monthlyRate, BigDecimal installmentAmount, BigDecimal totalPaid,
                             BigDecimal totalInterest, BigDecimal cetMonthly, BigDecimal cetYearly,
                             LocalDate firstDueDate, List<InstallmentView> schedule) {}

    public record Output(UUID loanId, UUID accountId, String status, BigDecimal released, BigDecimal iof,
                         BigDecimal financed, BigDecimal monthlyRate, BigDecimal cetMonthly, BigDecimal cetYearly,
                         int dueDay, LocalDate contractDate, LocalDateTime closedAt, int paidInstallments,
                         BigDecimal outstanding, List<InstallmentView> installments) {}

    /** @param amount o que saiu (ou sairia) da conta */
    public record Payment(UUID loanId, String status, BigDecimal amount, Integer installment, BigDecimal lateCharges,
                          BigDecimal discount) {}

    private final LoanRepository loanRepository;
    private final LoanAccountGateway accounts;
    private final LoanEventPublisher events;

    public PersonalLoans(LoanRepository loanRepository, LoanAccountGateway accounts, LoanEventPublisher events) {
        this.loanRepository = loanRepository;
        this.accounts = accounts;
        this.events = events;
    }

    public Offer offer(UUID accountId) {
        requireAccount(accountId);
        LocalDate today = DomainTime.today();
        Money income = monthlyIncome(accountId);
        Money maxInstallment = LoanPolicy.maxInstallment(income);
        Money maxAmount = LoanPolicy.maxAmount(maxInstallment, LoanPolicy.MAX_INSTALLMENTS, DEFAULT_DUE_DAY, today);
        return new Offer(income.amount(), maxInstallment.amount(), maxAmount.amount(), LoanPolicy.MAX_INSTALLMENTS,
                LoanPolicy.MONTHLY_RATE, loanRepository.findActiveByAccountId(accountId).isPresent());
    }

    /** Não muda nada. Recusa a parcela que não cabe na renda, como a contratação. */
    public Simulation simulate(UUID accountId, BigDecimal amount, Integer installments, Integer dueDay) {
        requireAccount(accountId);
        LoanPolicy.Quote quote = checkedQuote(accountId, amount, installments, dueDay);
        return toSimulation(quote);
    }

    @Transactional
    public Output contract(UUID accountId, BigDecimal amount, Integer installments, Integer dueDay) {
        UUID customerId = requireAccount(accountId);
        if (loanRepository.findActiveByAccountId(accountId).isPresent()) {
            throw new BusinessRuleException("Já existe um empréstimo ativo nesta conta: quite-o antes de contratar outro");
        }
        LoanPolicy.Quote quote = checkedQuote(accountId, amount, installments, dueDay);
        Loan loan = Loan.contract(accountId, customerId, dueDay(dueDay), quote, DomainTime.today());
        try {
            loanRepository.insert(loan);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("Já existe um empréstimo ativo nesta conta");
        }
        accounts.disburse(accountId, quote.released(), loan.getId());
        events.publishContracted(loan);
        return toOutput(loan);
    }

    public List<Output> list(UUID accountId) {
        requireAccount(accountId);
        return loanRepository.findByAccountId(accountId).stream().map(this::toOutput).toList();
    }

    public Output get(UUID accountId, UUID loanId) {
        return toOutput(find(accountId, loanId));
    }

    /** Paga a próxima parcela agora (antes do vencimento, com o desconto dos juros). */
    @Transactional
    public Payment payNext(UUID accountId, UUID loanId) {
        Loan loan = find(accountId, loanId);
        // o domínio decide a recusa (quitado, cancelado, sem parcela): aqui só se guarda qual era a próxima
        Integer number = loan.nextPending().map(Loan.Installment::number).orElse(null);
        Money paid = loan.payNext(DomainTime.today());
        loanRepository.update(loan);
        accounts.collect(accountId, paid, loanId);
        Loan.Installment done = loan.getInstallments().get(number - 1);
        events.publishInstallmentPaid(loan, done);
        return new Payment(loanId, loan.getStatus().name(), paid.amount(), number, done.lateCharges().amount(),
                done.discount().amount());
    }

    /** Simulação da quitação total hoje: atrasadas com encargos, futuras a valor presente. */
    public Payment payoffQuote(UUID accountId, UUID loanId) {
        Loan loan = find(accountId, loanId);
        LocalDate today = DomainTime.today();
        Money cost = loan.payoffCost(today);
        Money face = loan.pending().stream().map(Loan.Installment::amount).reduce(Money.ZERO, Money::add);
        return new Payment(loanId, "SIMULATION", cost.amount(), null,
                cost.isGreaterThan(face) ? cost.subtract(face).amount() : BigDecimal.ZERO,
                face.isGreaterThan(cost) ? face.subtract(cost).amount() : BigDecimal.ZERO);
    }

    @Transactional
    public Payment payOff(UUID accountId, UUID loanId) {
        Payment quote = payoffQuote(accountId, loanId);
        Loan loan = find(accountId, loanId);
        Money paid = loan.payOff(DomainTime.today());
        loanRepository.update(loan);
        accounts.collect(accountId, paid, loanId);
        events.publishPaidOff(loan, paid, Money.of(quote.discount()));
        return new Payment(loanId, loan.getStatus().name(), paid.amount(), null, quote.lateCharges(), quote.discount());
    }

    /** Arrependimento em até 7 dias: devolve o que recebeu, sem juros nem IOF. */
    @Transactional
    public Payment withdraw(UUID accountId, UUID loanId) {
        Loan loan = find(accountId, loanId);
        Money returned = loan.withdraw(DomainTime.today());
        loanRepository.update(loan);
        accounts.returnDisbursement(accountId, returned, loanId);
        events.publishWithdrawn(loan, returned);
        return new Payment(loanId, loan.getStatus().name(), returned.amount(), null, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private LoanPolicy.Quote checkedQuote(UUID accountId, BigDecimal amount, Integer installments, Integer dueDay) {
        if (amount == null) throw new ValidationException("Informe o valor (amount)");
        if (installments == null) throw new ValidationException("Informe o número de parcelas (installments)");
        LoanPolicy.Quote quote = LoanPolicy.quote(Money.of(amount.setScale(2, RoundingMode.HALF_UP)), installments,
                dueDay(dueDay), DomainTime.today());
        Money maxInstallment = LoanPolicy.maxInstallment(monthlyIncome(accountId));
        if (quote.installmentAmount().isGreaterThan(maxInstallment)) {
            throw new BusinessRuleException("A parcela (" + quote.installmentAmount().amount() + ") passa de 30% da sua renda "
                    + "estimada: a maior que cabe é " + maxInstallment.amount() + ". Diminua o valor ou aumente o prazo");
        }
        return quote;
    }

    private Money monthlyIncome(UUID accountId) {
        Money inflow = accounts.incomeSince(accountId, DomainTime.today().minusDays(INCOME_DAYS).atStartOfDay());
        return Money.of(inflow.amount().divide(BigDecimal.valueOf(3), 2, RoundingMode.DOWN));
    }

    private static int dueDay(Integer dueDay) {
        return dueDay == null ? DEFAULT_DUE_DAY : dueDay;
    }

    private UUID requireAccount(UUID accountId) {
        return accounts.customerOf(accountId).orElseThrow(() -> new NotFoundException("Conta não encontrada"));
    }

    private Loan find(UUID accountId, UUID loanId) {
        return loanRepository.findById(loanId)
                .filter(l -> l.getAccountId().equals(accountId))
                .orElseThrow(() -> new NotFoundException("Empréstimo não encontrado"));
    }

    private Simulation toSimulation(LoanPolicy.Quote q) {
        List<InstallmentView> schedule = q.schedule().stream()
                .map(i -> new InstallmentView(i.number(), i.dueDate(), i.amount().amount(), i.principal().amount(),
                        i.interest().amount(), "SIMULATION", null, null, null, null))
                .toList();
        return new Simulation(q.released().amount(), q.iof().amount(), q.financed().amount(), q.installments(),
                q.monthlyRate(), q.installmentAmount().amount(), q.totalPaid().amount(), q.totalInterest().amount(),
                q.cetMonthly(), q.cetYearly(), schedule.get(0).dueDate(), schedule);
    }

    private Output toOutput(Loan loan) {
        LocalDate today = DomainTime.today();
        List<InstallmentView> installments = loan.getInstallments().stream()
                .map(i -> new InstallmentView(i.number(), i.dueDate(), i.amount().amount(), i.principal().amount(),
                        i.interest().amount(), i.paid() ? "PAID" : (today.isAfter(i.dueDate()) ? "LATE" : "PENDING"),
                        i.paidAt(), i.paidAmount() == null ? null : i.paidAmount().amount(),
                        i.lateCharges() == null ? null : i.lateCharges().amount(),
                        i.discount() == null ? null : i.discount().amount()))
                .toList();
        BigDecimal outstanding = loan.getInstallments().stream().filter(i -> !i.paid())
                .map(i -> i.amount().amount()).reduce(BigDecimal.ZERO, BigDecimal::add);
        int paidCount = (int) loan.getInstallments().stream().filter(Loan.Installment::paid).count();
        return new Output(loan.getId(), loan.getAccountId(), loan.getStatus().name(), loan.getReleased().amount(),
                loan.getIof().amount(), loan.getFinanced().amount(), loan.getMonthlyRate(), loan.getCetMonthly(),
                loan.getCetYearly(), loan.getDueDay(), loan.getContractDate(), loan.getClosedAt(), paidCount,
                loan.getStatus() == Loan.Status.ACTIVE ? outstanding : BigDecimal.ZERO, installments);
    }
}
