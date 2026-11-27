package com.kage.card.domain.entity;

import com.kage.shared.domain.DomainTime;
import com.kage.card.domain.enums.InvoiceItemKind;
import com.kage.card.domain.enums.InvoiceStatus;
import com.kage.card.domain.service.RevolvingCreditPolicy;
import com.kage.card.domain.valueobject.InvoiceItem;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.ConflictException;
import com.kage.shared.domain.exception.ValidationException;
import com.kage.shared.domain.valueobject.Money;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Fatura mensal do cartão.
 *
 * Ciclo: OPEN (recebe compras) -> CLOSED (recebe pagamentos, totais ou parciais) -> PAID (quitada)
 * ou ROLLED_OVER (o que sobrou foi para a fatura seguinte, com os encargos do rotativo).
 */
public class Invoice {

    private final UUID id;
    private final UUID cardId;
    private final YearMonth referenceMonth;
    private LocalDate closingDate;
    private LocalDate dueDate;
    private InvoiceStatus status;
    private final List<InvoiceItem> items;
    private Money paidAmount;
    private LocalDateTime paidAt;
    private final LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private final Long version;

    private Invoice(UUID id, UUID cardId, YearMonth referenceMonth, LocalDate closingDate,
                    LocalDate dueDate, InvoiceStatus status, List<InvoiceItem> items, Money paidAmount,
                    LocalDateTime paidAt, LocalDateTime createdAt, LocalDateTime updatedAt, Long version) {
        this.id = id;
        this.cardId = cardId;
        this.referenceMonth = referenceMonth;
        this.closingDate = closingDate;
        this.dueDate = dueDate;
        this.status = status;
        this.items = new ArrayList<>(items);
        this.paidAmount = paidAmount;
        this.paidAt = paidAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    public static Invoice open(UUID cardId, YearMonth referenceMonth, int closingDay, int dueDay) {
        if (cardId == null) throw new ValidationException("Cartão é obrigatório");
        if (referenceMonth == null) throw new ValidationException("Mês de referência é obrigatório");

        LocalDate closingDate = referenceMonth.atDay(closingDay);
        LocalDate dueDate = dueDateOf(referenceMonth, closingDay, dueDay);

        LocalDateTime now = DomainTime.now();
        return new Invoice(UUID.randomUUID(), cardId, referenceMonth, closingDate, dueDate,
                InvoiceStatus.OPEN, List.of(), Money.ZERO, null, now, now, null);
    }

    public static Invoice reconstitute(UUID id, UUID cardId, YearMonth referenceMonth, LocalDate closingDate,
                                       LocalDate dueDate, InvoiceStatus status, List<InvoiceItem> items,
                                       Money paidAmount, LocalDateTime paidAt, LocalDateTime createdAt,
                                       LocalDateTime updatedAt, Long version) {
        return new Invoice(id, cardId, referenceMonth, closingDate, dueDate, status, items,
                paidAmount, paidAt, createdAt, updatedAt, version);
    }

    /** Vencimento da fatura do mês: no mesmo mês se o dia vem depois do fechamento; senão, no seguinte. */
    public static LocalDate dueDateOf(YearMonth referenceMonth, int closingDay, int dueDay) {
        return dueDay > closingDay ? referenceMonth.atDay(dueDay) : referenceMonth.plusMonths(1).atDay(dueDay);
    }

    /**
     * Troca do dia de vencimento: a fatura aberta passa a fechar e vencer nos dias novos. Só fatura
     * aberta — a fechada já tem o vencimento comunicado ao cliente.
     */
    public void reschedule(int closingDay, int dueDay) {
        if (this.status != InvoiceStatus.OPEN) throw new BusinessRuleException("Só fatura aberta muda de data");
        this.closingDate = referenceMonth.atDay(closingDay);
        this.dueDate = dueDateOf(referenceMonth, closingDay, dueDay);
        this.updatedAt = DomainTime.now();
    }

    /**
     * Uma compra feita em {@code date} pode entrar aqui: a fatura está aberta e ainda não passou do
     * fechamento. Normalmente o mês já garante isso; na troca de vencimento, a fatura atual mantém o
     * fechamento antigo enquanto o cartão já tem o novo.
     */
    public boolean acceptsPurchaseOn(LocalDate date) {
        return this.status == InvoiceStatus.OPEN && !date.isAfter(this.closingDate);
    }

    public static YearMonth referenceMonthFor(LocalDate purchaseDate, int closingDay) {
        YearMonth month = YearMonth.from(purchaseDate);
        return purchaseDate.getDayOfMonth() <= closingDay ? month : month.plusMonths(1);
    }

    public void addItem(UUID purchaseId, String description, Money amount, LocalDateTime purchasedAt) {
        if (this.status != InvoiceStatus.OPEN) throw new BusinessRuleException("Fatura não está aberta para novos lançamentos");
        boolean alreadyRegistered = items.stream().anyMatch(item -> item.purchaseId().equals(purchaseId));
        if (alreadyRegistered) throw new ConflictException("Compra já lançada nesta fatura");

        this.items.add(InvoiceItem.create(purchaseId, description, amount, purchasedAt));
        this.updatedAt = DomainTime.now();
    }

    /** Parcela de compra parcelada (a mesma compra tem uma linha em cada fatura do plano). */
    public void addInstallment(UUID purchaseId, String description, Money amount, LocalDateTime purchasedAt,
                               int number, int count) {
        addInstallment(purchaseId, description, amount, purchasedAt, number, count, null);
    }

    /** @param virtualCardId cartão virtual usado na compra; nulo = o físico */
    public void addInstallment(UUID purchaseId, String description, Money amount, LocalDateTime purchasedAt,
                               int number, int count, UUID virtualCardId) {
        if (this.status != InvoiceStatus.OPEN) throw new BusinessRuleException("Fatura não está aberta para novos lançamentos");
        if (items.stream().anyMatch(item -> item.purchaseId().equals(purchaseId))) {
            throw new ConflictException("Compra já lançada nesta fatura");
        }
        this.items.add(InvoiceItem.installment(purchaseId, description, amount, purchasedAt, number, count, virtualCardId));
        this.updatedAt = DomainTime.now();
    }

    /** Parcela do parcelamento de outra fatura. Idempotente pela fatura de origem. */
    public void addInvoiceInstallment(UUID sourceInvoiceId, String description, Money amount, LocalDateTime at,
                                      int number, int count) {
        if (this.status != InvoiceStatus.OPEN) throw new BusinessRuleException("Fatura não está aberta para novos lançamentos");
        InvoiceItem installment = InvoiceItem.invoiceInstallment(sourceInvoiceId, description, amount, at, number, count);
        if (items.stream().anyMatch(item -> item.purchaseId().equals(installment.purchaseId()))) return;
        this.items.add(installment);
        this.updatedAt = DomainTime.now();
    }

    /** Lança saldo trazido ou encargo do rotativo. Valor zero (ex.: cortado pelo teto) não vira linha. */
    public void addCharge(InvoiceItemKind kind, UUID sourceInvoiceId, String description, Money amount, LocalDateTime at) {
        if (this.status != InvoiceStatus.OPEN) throw new BusinessRuleException("Fatura não está aberta para novos lançamentos");
        if (kind == InvoiceItemKind.PURCHASE) throw new ValidationException("Compra entra por addItem");
        if (kind.isCredit()) throw new ValidationException("Crédito entra por addCredit");
        if (amount.isZero()) return;

        InvoiceItem charge = InvoiceItem.charge(kind, sourceInvoiceId, description, amount, at);
        if (items.stream().anyMatch(item -> item.purchaseId().equals(charge.purchaseId()))) return; // já lançado
        this.items.add(charge);
        this.updatedAt = DomainTime.now();
    }

    /**
     * Tira desta fatura a parcela do plano {@code planId} (antecipação: ela vai para a fatura atual).
     * Só fatura aberta — a parcela de uma fatura já fechada é cobrada nela.
     */
    public InvoiceItem removeInstallment(UUID planId) {
        if (this.status != InvoiceStatus.OPEN) throw new BusinessRuleException("Só dá para antecipar parcela de fatura aberta");
        InvoiceItem item = items.stream()
                .filter(i -> i.purchaseId().equals(planId) && i.installmentNumber() != null)
                .findFirst()
                .orElseThrow(() -> new BusinessRuleException("Parcela não está nesta fatura"));
        this.items.remove(item);
        this.updatedAt = DomainTime.now();
        return item;
    }

    /** Lança a antecipação de parcelas futuras. Idempotente pelo id da antecipação. */
    public void addAnticipation(InvoiceItem anticipation) {
        if (this.status != InvoiceStatus.OPEN) throw new BusinessRuleException("Fatura não está aberta para novos lançamentos");
        if (anticipation.kind() != InvoiceItemKind.ANTICIPATION) throw new ValidationException("Lançamento não é uma antecipação");
        if (items.stream().anyMatch(item -> item.purchaseId().equals(anticipation.purchaseId()))) return;
        this.items.add(anticipation);
        this.updatedAt = DomainTime.now();
    }

    /**
     * Lança um crédito (compra contestada, saldo credor da fatura anterior). Idempotente pela origem.
     * Valor zero não vira linha.
     */
    public void addCredit(InvoiceItemKind kind, UUID sourceId, String description, Money amount, LocalDateTime at) {
        if (this.status != InvoiceStatus.OPEN) throw new BusinessRuleException("Fatura não está aberta para novos lançamentos");
        if (amount.isZero()) return;
        InvoiceItem credit = InvoiceItem.credit(kind, sourceId, description, amount, at);
        if (items.stream().anyMatch(item -> item.purchaseId().equals(credit.purchaseId()))) return;
        this.items.add(credit);
        this.updatedAt = DomainTime.now();
    }

    /**
     * Tira desta fatura a linha da compra {@code purchaseId} (contestação: a cobrança fica suspensa).
     * Só fatura aberta — a de uma fatura fechada vira crédito na fatura atual.
     */
    public InvoiceItem removePurchase(UUID purchaseId) {
        if (this.status != InvoiceStatus.OPEN) throw new BusinessRuleException("Só dá para tirar compra de fatura aberta");
        InvoiceItem item = items.stream()
                .filter(i -> i.purchaseId().equals(purchaseId) && i.isPurchase())
                .findFirst()
                .orElseThrow(() -> new BusinessRuleException("Compra não está nesta fatura"));
        this.items.remove(item);
        this.updatedAt = DomainTime.now();
        return item;
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    /** Soma do que a fatura cobra (sem os créditos). */
    public Money charges() {
        return items.stream()
                .filter(item -> !item.isCredit())
                .map(InvoiceItem::amount)
                .reduce(Money.ZERO, Money::add);
    }

    /** Soma dos créditos lançados. */
    public Money credits() {
        return items.stream()
                .filter(InvoiceItem::isCredit)
                .map(InvoiceItem::amount)
                .reduce(Money.ZERO, Money::add);
    }

    /** O que a fatura cobra, já abatidos os créditos. Nunca negativo: o que sobra é o {@link #creditBalance()}. */
    public Money total() {
        Money charges = charges();
        Money credits = credits();
        return credits.isGreaterThan(charges) ? Money.ZERO : charges.subtract(credits);
    }

    /** Crédito que passou do que a fatura cobra: no fechamento, vai para a fatura seguinte. */
    public Money creditBalance() {
        Money charges = charges();
        Money credits = credits();
        return credits.isGreaterThan(charges) ? credits.subtract(charges) : Money.ZERO;
    }

    /** Parte dos créditos que de fato abateu cobranças desta fatura. */
    public Money absorbedCredits() {
        return credits().subtract(creditBalance());
    }

    /** Quanto ainda falta pagar. */
    public Money remaining() {
        return total().subtract(paidAmount);
    }

    public Money minimumPayment() {
        return RevolvingCreditPolicy.minimumPayment(total());
    }

    /** A fatura carrega saldo de uma anterior: quitá-la encerra a dívida do rotativo. */
    public boolean hasPreviousBalance() {
        return items.stream().anyMatch(item -> item.kind() == InvoiceItemKind.PREVIOUS_BALANCE);
    }

    public void close() {
        if (this.status != InvoiceStatus.OPEN) throw new BusinessRuleException("Somente faturas abertas podem ser fechadas");
        this.status = InvoiceStatus.CLOSED;
        this.updatedAt = DomainTime.now();
    }

    /**
     * Pagamento total ou parcial. Pode haver vários até o fechamento seguinte; quando o saldo zera, a
     * fatura vira PAID. Pagar menos que o mínimo é permitido (o banco não recusa dinheiro), mas na
     * virada conta como atraso.
     */
    public void registerPayment(Money amount, LocalDateTime now) {
        requirePayable();
        if (amount == null || amount.isZero()) throw new ValidationException("Valor do pagamento deve ser maior que zero");
        if (amount.isGreaterThan(remaining())) {
            throw new BusinessRuleException("Valor maior que o saldo devedor da fatura (" + remaining().amount() + ")");
        }
        this.paidAmount = this.paidAmount.add(amount);
        if (remaining().isZero()) {
            this.status = InvoiceStatus.PAID;
            this.paidAt = now;
        }
        this.updatedAt = now;
    }

    /** Paga o saldo devedor inteiro (fatura zerada é quitada sem pagamento). */
    public void pay() {
        requirePayable();
        LocalDateTime now = DomainTime.now();
        if (remaining().isZero()) {
            this.status = InvoiceStatus.PAID;
            this.paidAt = now;
            this.updatedAt = now;
            return;
        }
        registerPayment(remaining(), now);
    }

    /**
     * Virada: o saldo devedor sai desta fatura e vai para a seguinte (quem lança é o fechamento da
     * seguinte). Esta não aceita mais pagamentos — o cliente paga a nova.
     *
     * @return o saldo que foi levado
     */
    public Money rollOver(LocalDateTime now) {
        if (this.status != InvoiceStatus.CLOSED) throw new BusinessRuleException("Somente faturas fechadas com saldo podem ir para o rotativo");
        Money carried = remaining();
        if (carried.isZero()) throw new BusinessRuleException("Fatura sem saldo devedor");
        this.status = InvoiceStatus.ROLLED_OVER;
        this.updatedAt = now;
        return carried;
    }

    /**
     * Parcelamento: o saldo devedor sai desta fatura e vira parcelas nas seguintes (quem lança é o
     * use case). Esta não aceita mais pagamento nem vai para o rotativo.
     *
     * @return o saldo parcelado
     */
    public Money finance(LocalDateTime now) {
        if (this.status == InvoiceStatus.FINANCED) throw new BusinessRuleException("Fatura já foi parcelada");
        if (this.status != InvoiceStatus.CLOSED) throw new BusinessRuleException("Somente faturas fechadas com saldo podem ser parceladas");
        Money financed = remaining();
        if (financed.isZero()) throw new BusinessRuleException("Fatura sem saldo devedor");
        this.status = InvoiceStatus.FINANCED;
        this.updatedAt = now;
        return financed;
    }

    private void requirePayable() {
        if (this.status == InvoiceStatus.ROLLED_OVER) {
            throw new BusinessRuleException("O saldo desta fatura foi para a fatura seguinte: pague a fatura atual");
        }
        if (this.status == InvoiceStatus.FINANCED) {
            throw new BusinessRuleException("Esta fatura foi parcelada: as parcelas estão nas faturas seguintes");
        }
        if (this.status != InvoiceStatus.CLOSED) throw new BusinessRuleException("Somente faturas fechadas podem ser pagas");
    }

    public boolean isOverdue(LocalDate today) {
        return this.status == InvoiceStatus.CLOSED && today.isAfter(this.dueDate);
    }

    public UUID getId() { return id; }
    public UUID getCardId() { return cardId; }
    public YearMonth getReferenceMonth() { return referenceMonth; }
    public LocalDate getClosingDate() { return closingDate; }
    public LocalDate getDueDate() { return dueDate; }
    public InvoiceStatus getStatus() { return status; }
    public List<InvoiceItem> getItems() { return Collections.unmodifiableList(items); }
    public Money getPaidAmount() { return paidAmount; }
    public LocalDateTime getPaidAt() { return paidAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
}
