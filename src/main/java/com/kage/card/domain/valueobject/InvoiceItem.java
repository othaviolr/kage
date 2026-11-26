package com.kage.card.domain.valueobject;

import com.kage.card.domain.enums.InvoiceItemKind;
import com.kage.shared.domain.exception.ValidationException;
import com.kage.shared.domain.valueobject.Money;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Linha da fatura. Compras, parcelas e encargos do rotativo moram no mesmo lugar para o total ser
 * sempre a soma das linhas, e o {@code kind} diz o que é cada uma.
 *
 * @param purchaseId        para compras, o id da compra (idempotência; uma compra parcelada tem o
 *                          mesmo id em todas as faturas); para encargos/parcelamento da fatura, um id
 *                          determinístico derivado da fatura de origem — a mesma operação não é
 *                          lançada duas vezes
 * @param installmentNumber parcela desta linha (1..N); nulo para encargos
 * @param installmentCount  total de parcelas (1 = à vista); nulo para encargos
 * @param virtualCardId     compra feita com um cartão virtual: qual; nulo para o físico e para encargos
 */
public record InvoiceItem(UUID id, UUID purchaseId, String description, Money amount, LocalDateTime purchasedAt,
                          InvoiceItemKind kind, Integer installmentNumber, Integer installmentCount,
                          UUID virtualCardId) {

    public InvoiceItem(UUID id, UUID purchaseId, String description, Money amount, LocalDateTime purchasedAt,
                       InvoiceItemKind kind, Integer installmentNumber, Integer installmentCount) {
        this(id, purchaseId, description, amount, purchasedAt, kind, installmentNumber, installmentCount, null);
    }

    public InvoiceItem {
        if (virtualCardId != null && kind != InvoiceItemKind.PURCHASE) {
            throw new ValidationException("Só compra tem cartão virtual");
        }
        if (id == null) throw new ValidationException("Identificador do lançamento é obrigatório");
        if (purchaseId == null) throw new ValidationException("Identificador da compra é obrigatório");
        if (description == null || description.isBlank()) throw new ValidationException("Descrição do lançamento é obrigatória");
        if (amount == null || amount.isZero()) throw new ValidationException("Valor do lançamento deve ser maior que zero");
        if (purchasedAt == null) throw new ValidationException("Data da compra é obrigatória");
        if (kind == null) throw new ValidationException("Tipo do lançamento é obrigatório");
        if ((installmentNumber == null) != (installmentCount == null)) {
            throw new ValidationException("Parcela e total de parcelas andam juntos");
        }
        if (installmentNumber != null && (installmentNumber < 1 || installmentNumber > installmentCount)) {
            throw new ValidationException("Parcela inválida: " + installmentNumber + "/" + installmentCount);
        }
    }

    public static InvoiceItem create(UUID purchaseId, String description, Money amount, LocalDateTime purchasedAt) {
        return installment(purchaseId, description, amount, purchasedAt, 1, 1);
    }

    public static InvoiceItem installment(UUID purchaseId, String description, Money amount, LocalDateTime purchasedAt,
                                          int number, int count) {
        return installment(purchaseId, description, amount, purchasedAt, number, count, null);
    }

    public static InvoiceItem installment(UUID purchaseId, String description, Money amount, LocalDateTime purchasedAt,
                                          int number, int count, UUID virtualCardId) {
        return new InvoiceItem(UUID.randomUUID(), purchaseId, description, amount, purchasedAt, InvoiceItemKind.PURCHASE,
                number, count, virtualCardId);
    }

    public static InvoiceItem charge(InvoiceItemKind kind, UUID sourceInvoiceId, String description, Money amount,
                                     LocalDateTime at) {
        return new InvoiceItem(UUID.randomUUID(), deterministicId(kind, sourceInvoiceId), description, amount, at, kind,
                null, null);
    }

    /** Parcela do parcelamento de uma fatura: mesmo id em todas as faturas que recebem o plano. */
    public static InvoiceItem invoiceInstallment(UUID sourceInvoiceId, String description, Money amount, LocalDateTime at,
                                                 int number, int count) {
        return new InvoiceItem(UUID.randomUUID(), deterministicId(InvoiceItemKind.INVOICE_INSTALLMENT, sourceInvoiceId),
                description, amount, at, InvoiceItemKind.INVOICE_INSTALLMENT, number, count);
    }

    /** purchaseId da linha de encargo/saldo {@code kind} que a fatura {@code sourceInvoiceId} gerou na seguinte. */
    public static UUID chargeId(InvoiceItemKind kind, UUID sourceInvoiceId) {
        return deterministicId(kind, sourceInvoiceId);
    }

    /** Id do plano do parcelamento da fatura {@code sourceInvoiceId} (o purchaseId das parcelas dele). */
    public static UUID invoiceInstallmentPlanId(UUID sourceInvoiceId) {
        return deterministicId(InvoiceItemKind.INVOICE_INSTALLMENT, sourceInvoiceId);
    }

    /**
     * Antecipação das parcelas de um plano a partir da {@code firstNumber}: o id sai do plano e da
     * primeira parcela antecipada, então duas antecipações do mesmo plano não colidem.
     */
    public static InvoiceItem anticipation(UUID planId, int firstNumber, String description, Money amount, LocalDateTime at) {
        UUID id = UUID.nameUUIDFromBytes((InvoiceItemKind.ANTICIPATION.name() + ":" + planId + ":" + firstNumber).getBytes());
        return new InvoiceItem(UUID.randomUUID(), id, description, amount, at, InvoiceItemKind.ANTICIPATION, null, null);
    }

    /**
     * Crédito (contestação ou saldo credor trazido): {@code sourceId} é a contestação ou a fatura de
     * origem, então o mesmo crédito não entra duas vezes.
     */
    public static InvoiceItem credit(InvoiceItemKind kind, UUID sourceId, String description, Money amount,
                                     LocalDateTime at) {
        if (kind == null || !kind.isCredit()) throw new ValidationException("Lançamento não é um crédito");
        return new InvoiceItem(UUID.randomUUID(), deterministicId(kind, sourceId), description, amount, at, kind,
                null, null);
    }

    public boolean isCredit() {
        return kind.isCredit();
    }

    private static UUID deterministicId(InvoiceItemKind kind, UUID sourceInvoiceId) {
        return UUID.nameUUIDFromBytes((kind.name() + ":" + sourceInvoiceId).getBytes());
    }

    public boolean isPurchase() {
        return kind == InvoiceItemKind.PURCHASE;
    }
}
