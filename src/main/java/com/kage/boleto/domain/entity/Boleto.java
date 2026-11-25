package com.kage.boleto.domain.entity;

import com.kage.boleto.domain.service.BoletoChargePolicy;
import com.kage.boleto.domain.valueobject.BoletoCode;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.ValidationException;
import com.kage.shared.domain.valueobject.Money;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Boleto de cobrança emitido por um cliente do Kage (o beneficiário) para receber de alguém (o
 * pagador). Pago por outro cliente do Kage, liquida na hora; pago em outro banco, chega pela
 * compensação (simulada pelo backoffice).
 *
 * Campo livre do código: agência(4) + conta(8) + nosso número(13).
 */
public class Boleto {

    public static final int MAX_DESCRIPTION = 140;
    /** Depois do vencimento, ainda pode ser pago (com encargos) por este tempo. */
    public static final int DEFAULT_DAYS_PAYABLE_AFTER_DUE = 59;

    public enum Status { OPEN, PAID, CANCELLED }

    private final UUID id;
    private final UUID beneficiaryAccountId;
    private final long ourNumber;
    private final BoletoCode code;
    private final Money amount;
    private final LocalDate dueDate;
    private final LocalDate payableUntil;
    private final BigDecimal finePercent;
    private final BigDecimal monthlyInterestPercent;
    private final String payerName;
    private final String payerDocument;
    private final String description;
    private Status status;
    private Money paidAmount;
    private LocalDateTime paidAt;
    private String paidVia;
    private final LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private final Long version;

    private Boleto(UUID id, UUID beneficiaryAccountId, long ourNumber, BoletoCode code, Money amount, LocalDate dueDate,
                   LocalDate payableUntil, BigDecimal finePercent, BigDecimal monthlyInterestPercent, String payerName,
                   String payerDocument, String description, Status status, Money paidAmount, LocalDateTime paidAt,
                   String paidVia, LocalDateTime createdAt, LocalDateTime updatedAt, Long version) {
        this.id = id;
        this.beneficiaryAccountId = beneficiaryAccountId;
        this.ourNumber = ourNumber;
        this.code = code;
        this.amount = amount;
        this.dueDate = dueDate;
        this.payableUntil = payableUntil;
        this.finePercent = finePercent;
        this.monthlyInterestPercent = monthlyInterestPercent;
        this.payerName = payerName;
        this.payerDocument = payerDocument;
        this.description = description;
        this.status = status;
        this.paidAmount = paidAmount;
        this.paidAt = paidAt;
        this.paidVia = paidVia;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    public record IssueRequest(UUID beneficiaryAccountId, String branch, String accountNumber, long ourNumber,
                               String bankCode, Money amount, LocalDate dueDate, BigDecimal finePercent,
                               BigDecimal monthlyInterestPercent, String payerName, String payerDocument,
                               String description) {}

    public static Boleto issue(IssueRequest r, LocalDate today) {
        if (r.amount() == null || r.amount().isZero()) throw new ValidationException("Valor do boleto deve ser maior que zero");
        if (r.dueDate() == null || r.dueDate().isBefore(today)) throw new ValidationException("Vencimento não pode ser no passado");
        if (r.dueDate().isAfter(today.plusYears(1))) throw new ValidationException("Vencimento em até 1 ano");
        if (r.payerName() == null || r.payerName().isBlank()) throw new ValidationException("Informe o nome do pagador");
        String document = r.payerDocument() == null ? "" : r.payerDocument().replaceAll("\\D", "");
        if (document.length() != 11 && document.length() != 14) throw new ValidationException("CPF ou CNPJ do pagador inválido");
        if (r.description() != null && r.description().length() > MAX_DESCRIPTION) {
            throw new ValidationException("Descrição com no máximo " + MAX_DESCRIPTION + " caracteres");
        }
        BigDecimal fine = percent(r.finePercent(), BoletoChargePolicy.MAX_FINE_PERCENT, "Multa");
        BigDecimal interest = percent(r.monthlyInterestPercent(), BoletoChargePolicy.MAX_MONTHLY_INTEREST_PERCENT, "Juros de mora");

        String free = "%4s".formatted(r.branch()).replace(' ', '0') + "%8s".formatted(r.accountNumber()).replace(' ', '0')
                + "%013d".formatted(r.ourNumber());
        BoletoCode code = BoletoCode.build(r.bankCode(), r.dueDate(), r.amount().amount(), free);
        LocalDateTime now = LocalDateTime.now();
        return new Boleto(UUID.randomUUID(), r.beneficiaryAccountId(), r.ourNumber(), code, r.amount(), r.dueDate(),
                r.dueDate().plusDays(DEFAULT_DAYS_PAYABLE_AFTER_DUE), fine, interest, r.payerName().strip(), document,
                r.description() == null ? null : r.description().strip(), Status.OPEN, null, null, null, now, now, null);
    }

    public static Boleto reconstitute(UUID id, UUID beneficiaryAccountId, long ourNumber, BoletoCode code, Money amount,
                                      LocalDate dueDate, LocalDate payableUntil, BigDecimal finePercent,
                                      BigDecimal monthlyInterestPercent, String payerName, String payerDocument,
                                      String description, Status status, Money paidAmount, LocalDateTime paidAt,
                                      String paidVia, LocalDateTime createdAt, LocalDateTime updatedAt, Long version) {
        return new Boleto(id, beneficiaryAccountId, ourNumber, code, amount, dueDate, payableUntil, finePercent,
                monthlyInterestPercent, payerName, payerDocument, description, status, paidAmount, paidAt, paidVia,
                createdAt, updatedAt, version);
    }

    /** Quanto custa pagar em {@code date}: valor + multa + juros. @throws BusinessRuleException se não pode mais ser pago */
    public BoletoChargePolicy.Charges chargesOn(LocalDate date) {
        requirePayable(date);
        return BoletoChargePolicy.on(amount.amount(), dueDate, date, finePercent, monthlyInterestPercent);
    }

    public Money totalOn(LocalDate date) {
        return amount.add(Money.of(chargesOn(date).total()));
    }

    /** @param via "KAGE" (outro cliente do Kage) ou "COMPENSACAO" (outro banco) */
    public Money pay(LocalDate date, String via) {
        Money total = totalOn(date);
        this.status = Status.PAID;
        this.paidAmount = total;
        this.paidAt = LocalDateTime.now();
        this.paidVia = via;
        this.updatedAt = this.paidAt;
        return total;
    }

    public void cancel() {
        if (status != Status.OPEN) throw new BusinessRuleException("Só boleto em aberto pode ser cancelado");
        this.status = Status.CANCELLED;
        this.updatedAt = LocalDateTime.now();
    }

    private void requirePayable(LocalDate date) {
        if (status == Status.PAID) throw new BusinessRuleException("Boleto já foi pago");
        if (status == Status.CANCELLED) throw new BusinessRuleException("Boleto cancelado pelo beneficiário");
        if (date.isAfter(payableUntil)) throw new BusinessRuleException("Boleto passou do prazo para pagamento");
    }

    /** Situação para exibir: em aberto depois do vencimento aparece como OVERDUE; depois do prazo, EXPIRED. */
    public String displayStatus(LocalDate today) {
        if (status != Status.OPEN) return status.name();
        if (today.isAfter(payableUntil)) return "EXPIRED";
        if (today.isAfter(BoletoChargePolicy.graceDate(dueDate))) return "OVERDUE";
        return "OPEN";
    }

    private static BigDecimal percent(BigDecimal value, BigDecimal max, String label) {
        BigDecimal v = value == null ? BigDecimal.ZERO : value;
        if (v.signum() < 0 || v.compareTo(max) > 0) {
            throw new ValidationException(label + " entre 0% e " + max.stripTrailingZeros().toPlainString() + "%");
        }
        return v.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    public UUID getId() { return id; }
    public UUID getBeneficiaryAccountId() { return beneficiaryAccountId; }
    public long getOurNumber() { return ourNumber; }
    public BoletoCode getCode() { return code; }
    public Money getAmount() { return amount; }
    public LocalDate getDueDate() { return dueDate; }
    public LocalDate getPayableUntil() { return payableUntil; }
    public BigDecimal getFinePercent() { return finePercent; }
    public BigDecimal getMonthlyInterestPercent() { return monthlyInterestPercent; }
    public String getPayerName() { return payerName; }
    public String getPayerDocument() { return payerDocument; }
    public String getDescription() { return description; }
    public Status getStatus() { return status; }
    public Money getPaidAmount() { return paidAmount; }
    public LocalDateTime getPaidAt() { return paidAt; }
    public String getPaidVia() { return paidVia; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
}
