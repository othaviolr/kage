package com.kage.loan.application.usecase;

import com.kage.loan.domain.entity.Loan;
import com.kage.loan.domain.repository.LoanRepository;
import com.kage.loan.domain.service.LoanHolderDirectory;
import com.kage.shared.domain.DomainTime;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.valueobject.Money;
import com.kage.shared.domain.valueobject.ReceiptCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Carta de quitação do empréstimo: declaração do Kage de que o contrato foi pago por inteiro e não há
 * mais nada em aberto. Só sai de empréstimo quitado (PAID). Do acordo de renegociação quitado, a carta
 * cobre também o empréstimo que ele substituiu; o renegociado sozinho não tem carta (a dívida continuou
 * no acordo). Código de autenticação: o banco recalcula a partir do contrato.
 */
public class LoanDischargeLetters {

    /**
     * @param originalContractDate quando o acordo é uma renegociação: a data do empréstimo que ele substituiu
     * @param paidInInstallments   o que saiu da conta nas parcelas (com encargos e descontos)
     */
    public record Letter(UUID loanId, String holderName, String holderCpf, boolean agreement, LocalDate contractDate,
                         LocalDate originalContractDate, BigDecimal financed, int installments, BigDecimal downPayment,
                         BigDecimal paidInInstallments, LocalDateTime paidOffAt, String authenticationCode,
                         LocalDateTime generatedAt) {}

    public record Document(String fileName, byte[] content) {}

    /** Desenha o documento. A implementação (PDFBox) fica na infraestrutura. */
    public interface Renderer {
        byte[] render(Letter letter);
    }

    private final LoanRepository loanRepository;
    private final LoanHolderDirectory holders;
    private final Renderer renderer;

    public LoanDischargeLetters(LoanRepository loanRepository, LoanHolderDirectory holders, Renderer renderer) {
        this.loanRepository = loanRepository;
        this.holders = holders;
        this.renderer = renderer;
    }

    public Document pdf(UUID accountId, UUID loanId) {
        Letter letter = letter(accountId, loanId);
        return new Document("carta-de-quitacao-kage-" + letter.paidOffAt().toLocalDate() + ".pdf", renderer.render(letter));
    }

    public Letter letter(UUID accountId, UUID loanId) {
        Loan loan = loanRepository.findById(loanId)
                .filter(l -> l.getAccountId().equals(accountId))
                .orElseThrow(() -> new NotFoundException("Empréstimo não encontrado"));
        switch (loan.getStatus()) {
            case ACTIVE -> throw new BusinessRuleException(
                    "Ainda há parcelas em aberto: a carta de quitação sai quando o empréstimo for quitado");
            case CANCELLED -> throw new BusinessRuleException(
                    "Empréstimo cancelado por arrependimento: não houve dívida a quitar");
            case RENEGOTIATED -> throw new BusinessRuleException(
                    "Empréstimo renegociado: a dívida continuou no acordo, e a carta sai quando o acordo for quitado");
            case PAID -> { }
        }
        LoanHolderDirectory.Holder holder = holders.holderOf(loan.getCustomerId())
                .orElseThrow(() -> new NotFoundException("Cliente não encontrado"));
        boolean agreement = loan.getOrigin() == Loan.Origin.RENEGOTIATION;
        LocalDate originalContractDate = agreement
                ? loanRepository.findById(loan.getRenegotiatedFrom()).map(Loan::getContractDate).orElse(null)
                : null;
        Money paid = loan.getInstallments().stream().map(Loan.Installment::paidAmount).reduce(Money.ZERO, Money::add);
        BigDecimal downPayment = loan.getDownPayment() == null ? null : loan.getDownPayment().amount();
        String code = ReceiptCode.of("QUITACAO", loan.getId(), loan.getCustomerId(), loan.getClosedAt(),
                paid.amount().stripTrailingZeros().toPlainString());
        return new Letter(loan.getId(), holder.fullName(), formatCpf(holder.cpf()), agreement, loan.getContractDate(),
                originalContractDate, loan.getFinanced().amount(), loan.getInstallments().size(), downPayment, paid.amount(),
                loan.getClosedAt(), code, DomainTime.now());
    }

    static String formatCpf(String cpf) {
        String digits = cpf.replaceAll("\\D", "");
        if (digits.length() != 11) return cpf;
        return digits.substring(0, 3) + "." + digits.substring(3, 6) + "." + digits.substring(6, 9) + "-" + digits.substring(9);
    }
}
