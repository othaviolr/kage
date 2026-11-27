package com.kage.card.domain.entity;

import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.ValidationException;
import com.kage.shared.domain.valueobject.Money;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Cobrança para pagar a fatura de outro banco: um boleto ou um PIX copia e cola, com valor fixo e
 * prazo. O dinheiro chega depois (compensação do boleto, liquidação do PIX) e só então a fatura
 * recebe o pagamento — até lá, a cobrança está pendente e a fatura não muda.
 */
public class InvoiceCharge {

    public enum Method { BOLETO, PIX }

    public enum Status { PENDING, PAID, CANCELLED }

    private final UUID id;
    private final UUID cardId;
    private final UUID invoiceId;
    private final String referenceMonth;
    private final Method method;
    private final Money amount;
    private final LocalDateTime expiresAt;
    /** Boleto: código de barras (44 dígitos). PIX: o copia e cola. */
    private final String code;
    /** PIX: identificador que volta com o pagamento. Boleto: o nosso número. */
    private final String reference;
    private Status status;
    private LocalDateTime paidAt;
    private final LocalDateTime createdAt;

    private InvoiceCharge(UUID id, UUID cardId, UUID invoiceId, String referenceMonth, Method method, Money amount,
                          LocalDateTime expiresAt, String code, String reference, Status status, LocalDateTime paidAt,
                          LocalDateTime createdAt) {
        this.id = id;
        this.cardId = cardId;
        this.invoiceId = invoiceId;
        this.referenceMonth = referenceMonth;
        this.method = method;
        this.amount = amount;
        this.expiresAt = expiresAt;
        this.code = code;
        this.reference = reference;
        this.status = status;
        this.paidAt = paidAt;
        this.createdAt = createdAt;
    }

    public static InvoiceCharge issue(UUID id, UUID cardId, UUID invoiceId, String referenceMonth, Method method,
                                      Money amount, LocalDateTime expiresAt, String code, String reference,
                                      LocalDateTime now) {
        if (method == null) throw new ValidationException("Forma de pagamento é obrigatória (PIX ou BOLETO)");
        if (amount == null || amount.isZero()) throw new ValidationException("Valor da cobrança deve ser maior que zero");
        if (!expiresAt.isAfter(now)) throw new IllegalArgumentException("Cobrança já nasceria vencida");
        return new InvoiceCharge(id, cardId, invoiceId, referenceMonth, method, amount, expiresAt, code, reference,
                Status.PENDING, null, now);
    }

    public static InvoiceCharge reconstitute(UUID id, UUID cardId, UUID invoiceId, String referenceMonth, Method method,
                                             Money amount, LocalDateTime expiresAt, String code, String reference,
                                             Status status, LocalDateTime paidAt, LocalDateTime createdAt) {
        return new InvoiceCharge(id, cardId, invoiceId, referenceMonth, method, amount, expiresAt, code, reference,
                status, paidAt, createdAt);
    }

    /**
     * O dinheiro chegou. {@code paidAt} é quando o cliente pagou no outro banco (o boleto compensa
     * depois): precisa estar dentro do prazo da cobrança.
     */
    public void settle(LocalDateTime paidAt, LocalDateTime now) {
        if (status == Status.PAID) throw new BusinessRuleException("Cobrança já foi paga");
        if (status == Status.CANCELLED) throw new BusinessRuleException("Cobrança cancelada: o código não vale mais");
        if (paidAt.isAfter(now)) throw new ValidationException("Data do pagamento no futuro");
        if (paidAt.isBefore(createdAt)) throw new ValidationException("Data do pagamento antes da cobrança existir");
        if (paidAt.isAfter(expiresAt)) throw new BusinessRuleException("Cobrança vencida: o código não vale mais");
        this.status = Status.PAID;
        this.paidAt = paidAt;
    }

    /** Um código novo da mesma forma de pagamento substitui este, ou o cliente desistiu. */
    public void cancel() {
        if (status == Status.PAID) throw new BusinessRuleException("Cobrança já foi paga");
        if (status == Status.CANCELLED) throw new BusinessRuleException("Cobrança já está cancelada");
        this.status = Status.CANCELLED;
    }

    /** PENDING passado do prazo aparece como EXPIRED (não precisa de job para isso). */
    public String displayStatus(LocalDateTime now) {
        return status == Status.PENDING && now.isAfter(expiresAt) ? "EXPIRED" : status.name();
    }

    public boolean isPending(LocalDateTime now) {
        return "PENDING".equals(displayStatus(now));
    }

    public UUID getId() { return id; }
    public UUID getCardId() { return cardId; }
    public UUID getInvoiceId() { return invoiceId; }
    public String getReferenceMonth() { return referenceMonth; }
    public Method getMethod() { return method; }
    public Money getAmount() { return amount; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public String getCode() { return code; }
    public String getReference() { return reference; }
    public Status getStatus() { return status; }
    public LocalDateTime getPaidAt() { return paidAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
