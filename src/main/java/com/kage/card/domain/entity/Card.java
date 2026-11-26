package com.kage.card.domain.entity;

import com.kage.shared.domain.DomainTime;
import com.kage.card.domain.enums.CardStatus;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.ValidationException;
import com.kage.shared.domain.valueobject.Money;

import java.time.LocalDateTime;
import java.util.UUID;

public class Card {

    private static final int MIN_DAY = 1;
    private static final int MAX_DAY = 28;

    private final UUID id;
    private final UUID customerId;
    private final UUID accountId;
    private Money creditLimit;
    private Money usedLimit;
    private final int closingDay;
    private final int dueDay;
    private CardStatus status;
    private boolean autoDebit;
    /** Dívida original que entrou no rotativo (base do teto da Lei 14.690/2023); zero sem rotativo. */
    private Money revolvingPrincipal;
    /** Juros e encargos do rotativo já cobrados sobre essa dívida. */
    private Money revolvingCharges;
    private final LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private final Long version;

    private Card(UUID id, UUID customerId, UUID accountId, Money creditLimit,
                 Money usedLimit, int closingDay, int dueDay, CardStatus status, boolean autoDebit,
                 Money revolvingPrincipal, Money revolvingCharges,
                 LocalDateTime createdAt, LocalDateTime updatedAt, Long version) {
        this.revolvingPrincipal = revolvingPrincipal;
        this.revolvingCharges = revolvingCharges;
        this.id = id;
        this.customerId = customerId;
        this.accountId = accountId;
        this.creditLimit = creditLimit;
        this.usedLimit = usedLimit;
        this.closingDay = closingDay;
        this.dueDay = dueDay;
        this.status = status;
        this.autoDebit = autoDebit;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    public static Card create(UUID customerId, UUID accountId, Money creditLimit, int closingDay, int dueDay) {
        if (customerId == null) throw new ValidationException("Cliente é obrigatório");
        if (accountId == null) throw new ValidationException("Conta é obrigatória");
        if (creditLimit == null || creditLimit.isZero()) throw new ValidationException("Limite de crédito deve ser maior que zero");
        validateDay(closingDay, "Dia de fechamento");
        validateDay(dueDay, "Dia de vencimento");

        LocalDateTime now = DomainTime.now();
        return new Card(UUID.randomUUID(), customerId, accountId, creditLimit, Money.ZERO, closingDay, dueDay, CardStatus.ACTIVE, false, Money.ZERO, Money.ZERO, now, now, null);
    }

    public static Card reconstitute(UUID id, UUID customerId, UUID accountId, Money creditLimit,
                                    Money usedLimit, int closingDay, int dueDay, CardStatus status, boolean autoDebit,
                                    Money revolvingPrincipal, Money revolvingCharges,
                                    LocalDateTime createdAt, LocalDateTime updatedAt, Long version) {
        return new Card(id, customerId, accountId, creditLimit, usedLimit,
                closingDay, dueDay, status, autoDebit, revolvingPrincipal, revolvingCharges, createdAt, updatedAt, version);
    }

    /** Zero quando os encargos do rotativo levaram a dívida além do limite. */
    public Money availableLimit() {
        return this.usedLimit.isGreaterThan(this.creditLimit) ? Money.ZERO : this.creditLimit.subtract(this.usedLimit);
    }

    public void authorizePurchase(Money amount) {
        if (this.status != CardStatus.ACTIVE) throw new BusinessRuleException("Cartão não está ativo");
        if (amount.isZero()) throw new ValidationException("Valor da compra deve ser maior que zero");
        if (amount.isGreaterThan(availableLimit())) throw new BusinessRuleException("Limite disponível insuficiente");
        this.usedLimit = this.usedLimit.add(amount);
        this.updatedAt = DomainTime.now();
    }

    public void releaseLimit(Money amount) {
        if (amount.isGreaterThan(this.usedLimit)) throw new BusinessRuleException("Valor a liberar excede o limite utilizado");
        this.usedLimit = this.usedLimit.subtract(amount);
        this.updatedAt = DomainTime.now();
    }

    /**
     * Crédito (de contestação) que abateu cobranças no fechamento: a dívida diminuiu, o limite volta.
     * O crédito só devolve limite quando é usado — enquanto ele está parado na fatura aberta, a dívida
     * que ele vai abater ainda existe.
     */
    public void absorbCredit(Money amount) {
        if (amount.isZero()) return;
        this.usedLimit = amount.isGreaterThan(this.usedLimit) ? Money.ZERO : this.usedLimit.subtract(amount);
        this.updatedAt = DomainTime.now();
    }

    /**
     * IOF lançado na fatura: aumenta a dívida (consome limite), mas não conta no teto da Lei 14.690 —
     * é imposto, não encargo do banco.
     */
    public void addTax(Money amount) {
        if (amount.isZero()) return;
        this.usedLimit = this.usedLimit.add(amount);
        this.updatedAt = DomainTime.now();
    }

    /**
     * Contestação negada: a compra volta a ser dívida. Não passa pela autorização — o cliente já fez a
     * compra; pode ultrapassar o limite (aí novas compras ficam bloqueadas até pagar).
     */
    public void reinstateCharge(Money amount) {
        if (amount.isZero()) return;
        this.usedLimit = this.usedLimit.add(amount);
        this.updatedAt = DomainTime.now();
    }

    public void block() {
        if (this.status == CardStatus.CANCELLED) throw new BusinessRuleException("Cartão já está cancelado");
        if (this.status == CardStatus.BLOCKED) throw new BusinessRuleException("Cartão já está bloqueado");
        this.status = CardStatus.BLOCKED;
        this.updatedAt = DomainTime.now();
    }

    public void unblock() {
        if (this.status != CardStatus.BLOCKED) throw new BusinessRuleException("Cartão não está bloqueado");
        this.status = CardStatus.ACTIVE;
        this.updatedAt = DomainTime.now();
    }

    public void cancel() {
        if (this.status == CardStatus.CANCELLED) throw new BusinessRuleException("Cartão já está cancelado");
        if (!this.usedLimit.isZero()) throw new BusinessRuleException("Não é possível cancelar um cartão com fatura em aberto");
        this.status = CardStatus.CANCELLED;
        this.updatedAt = DomainTime.now();
    }

    /**
     * Aplica os encargos de uma virada do rotativo respeitando o teto da Lei 14.690/2023: o total
     * cobrado sobre uma dívida nunca passa do valor dela. Na primeira virada, o saldo levado vira o
     * principal de referência. O que é cobrado aumenta a dívida, então consome limite (pode ultrapassar
     * o limite de crédito: aí novas compras ficam bloqueadas até pagar).
     *
     * @return quanto dos encargos propostos pode de fato ser cobrado
     */
    public Money applyRevolvingCharges(Money carriedBalance, Money proposedCharges) {
        if (this.revolvingPrincipal.isZero()) {
            this.revolvingPrincipal = carriedBalance;
        }
        Money room = this.revolvingCharges.isGreaterThan(this.revolvingPrincipal)
                ? Money.ZERO
                : this.revolvingPrincipal.subtract(this.revolvingCharges);
        Money applied = proposedCharges.isGreaterThan(room) ? room : proposedCharges;

        this.revolvingCharges = this.revolvingCharges.add(applied);
        this.usedLimit = this.usedLimit.add(applied);
        this.updatedAt = DomainTime.now();
        return applied;
    }

    /**
     * Desconto dos juros numa antecipação de parcelamento: a dívida diminui, então o limite volta, e
     * os juros deixam de contar no teto legal.
     */
    public void discountFinancedInterest(Money discount) {
        if (discount.isZero()) return;
        this.usedLimit = discount.isGreaterThan(this.usedLimit) ? Money.ZERO : this.usedLimit.subtract(discount);
        this.revolvingCharges = discount.isGreaterThan(this.revolvingCharges)
                ? Money.ZERO : this.revolvingCharges.subtract(discount);
        this.updatedAt = DomainTime.now();
    }

    /**
     * Encargos devolvidos (contestação procedente): deixam de contar no teto legal. O limite volta
     * quando o crédito do estorno abate cobranças, no fechamento (como todo crédito).
     */
    public void refundRevolvingCharges(Money refunded) {
        if (refunded.isZero()) return;
        this.revolvingCharges = refunded.isGreaterThan(this.revolvingCharges)
                ? Money.ZERO : this.revolvingCharges.subtract(refunded);
        this.updatedAt = DomainTime.now();
    }

    /** Dívida do rotativo quitada: o próximo uso do rotativo começa uma conta nova. */
    public void settleRevolving() {
        this.revolvingPrincipal = Money.ZERO;
        this.revolvingCharges = Money.ZERO;
        this.updatedAt = DomainTime.now();
    }

    public boolean hasRevolvingDebt() {
        return !this.revolvingPrincipal.isZero();
    }

    /**
     * Débito automático: no vencimento, a fatura fechada é paga com o saldo da conta vinculada. Opção
     * do cliente — sem ela, a fatura espera o pagamento manual.
     */
    public void enableAutoDebit() {
        if (this.status == CardStatus.CANCELLED) throw new BusinessRuleException("Cartão cancelado não pode ter débito automático");
        this.autoDebit = true;
        this.updatedAt = DomainTime.now();
    }

    public void disableAutoDebit() {
        this.autoDebit = false;
        this.updatedAt = DomainTime.now();
    }

    private static void validateDay(int day, String field) {
        if (day < MIN_DAY || day > MAX_DAY) {
            throw new ValidationException(field + " deve estar entre " + MIN_DAY + " e " + MAX_DAY);
        }
    }

    public UUID getId() { return id; }
    public UUID getCustomerId() { return customerId; }
    public UUID getAccountId() { return accountId; }
    public Money getCreditLimit() { return creditLimit; }
    public Money getUsedLimit() { return usedLimit; }
    public int getClosingDay() { return closingDay; }
    public int getDueDay() { return dueDay; }
    public CardStatus getStatus() { return status; }
    public boolean isAutoDebit() { return autoDebit; }
    public Money getRevolvingPrincipal() { return revolvingPrincipal; }
    public Money getRevolvingCharges() { return revolvingCharges; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
}
