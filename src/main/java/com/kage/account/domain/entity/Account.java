package com.kage.account.domain.entity;

import com.kage.account.domain.enums.AccountStatus;
import com.kage.account.domain.enums.AccountType;
import com.kage.account.domain.valueobject.Limits;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.valueobject.Money;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public class Account {

    private final UUID id;
    private final UUID customerId;
    private final String accountNumber;
    private final String accountDigit;
    private final String branch;
    private final AccountType type;
    private Money balance;
    private Money availableBalance;
    private Limits limits;
    private AccountStatus status;
    private final LocalDateTime openedAt;
    private LocalDateTime closedAt;
    private LocalDateTime updatedAt;
    private final Long version;

    private Account(UUID id, UUID customerId, String accountNumber, String accountDigit,
                    String branch, AccountType type, Money balance, Money availableBalance,
                    Limits limits, AccountStatus status, LocalDateTime openedAt,
                    LocalDateTime closedAt, LocalDateTime updatedAt, Long version) {
        this.id = id;
        this.customerId = customerId;
        this.accountNumber = accountNumber;
        this.accountDigit = accountDigit;
        this.branch = branch;
        this.type = type;
        this.balance = balance;
        this.availableBalance = availableBalance;
        this.limits = limits;
        this.status = status;
        this.openedAt = openedAt;
        this.closedAt = closedAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    public static Account create(UUID customerId, AccountType type, String accountNumber, String accountDigit) {
        return new Account(UUID.randomUUID(), customerId, accountNumber, accountDigit, "0001", type,
                Money.ZERO, Money.ZERO, Limits.defaultLimits(), AccountStatus.ACTIVE,
                LocalDateTime.now(), null, LocalDateTime.now(), null);
    }

    public static Account reconstitute(UUID id, UUID customerId, String accountNumber, String accountDigit,
                                       String branch, AccountType type, Money balance, Money availableBalance,
                                       Limits limits, AccountStatus status, LocalDateTime openedAt,
                                       LocalDateTime closedAt, LocalDateTime updatedAt, Long version) {
        return new Account(id, customerId, accountNumber, accountDigit, branch, type,
                balance, availableBalance, limits, status, openedAt, closedAt, updatedAt, version);
    }

    public void credit(Money amount) {
        if (this.status != AccountStatus.ACTIVE) throw new BusinessRuleException("Não é possível creditar em uma conta que não está ativa");
        this.balance = this.balance.add(amount);
        this.availableBalance = this.availableBalance.add(amount);
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Débito genérico (PIX, estorno): só garante conta ativa e saldo. Limites dependem do canal e do
     * que já foi movimentado no período, então são checados antes, por quem conhece o canal
     * ({@link #checkPixLimits}, {@link #withdraw}).
     */
    public void debit(Money amount) {
        if (this.status != AccountStatus.ACTIVE) throw new BusinessRuleException("Conta não está ativa");
        if (amount.isGreaterThan(this.availableBalance)) throw new BusinessRuleException("Saldo disponível insuficiente");
        this.balance = this.balance.subtract(amount);
        this.availableBalance = this.availableBalance.subtract(amount);
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * @param withdrawnToday soma dos saques já feitos hoje: o limite diário vale para o dia, não por saque
     */
    public void withdraw(Money amount, Money withdrawnToday) {
        if (this.status != AccountStatus.ACTIVE) throw new BusinessRuleException("Conta não está ativa");
        if (amount.isGreaterThan(this.availableBalance)) throw new BusinessRuleException("Saldo disponível insuficiente");
        if (withdrawnToday.add(amount).isGreaterThan(this.limits.dailyWithdrawalLimit())) {
            throw new BusinessRuleException("Valor excede o limite diário de saque (disponível hoje: "
                    + remaining(this.limits.dailyWithdrawalLimit(), withdrawnToday) + ")");
        }
        this.balance = this.balance.subtract(amount);
        this.availableBalance = this.availableBalance.subtract(amount);
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Regras para ENVIAR um PIX, checadas antes de a transação ser criada.
     *
     * @param sentToday   soma dos PIX enviados hoje (inclusive os ainda em processamento)
     * @param sentTonight soma dos PIX enviados na janela noturna atual
     * @param nightTime   se o envio acontece na janela noturna (20h–6h)
     */
    public void checkPixLimits(Money amount, Money sentToday, Money sentTonight, boolean nightTime) {
        if (this.status != AccountStatus.ACTIVE) throw new BusinessRuleException("Conta de origem não está ativa");
        if (amount.isGreaterThan(this.availableBalance)) throw new BusinessRuleException("Saldo insuficiente para realizar o PIX");
        if (sentToday.add(amount).isGreaterThan(this.limits.pixDailyLimit())) {
            throw new BusinessRuleException("Valor excede o limite diário de PIX (disponível hoje: "
                    + remaining(this.limits.pixDailyLimit(), sentToday) + ")");
        }
        if (nightTime && sentTonight.add(amount).isGreaterThan(this.limits.pixNightLimit())) {
            throw new BusinessRuleException("Valor excede o limite noturno de PIX (disponível até as 6h: "
                    + remaining(this.limits.pixNightLimit(), sentTonight) + ")");
        }
    }

    private static BigDecimal remaining(Money limit, Money used) {
        return used.isGreaterThan(limit) ? BigDecimal.ZERO.setScale(2) : limit.subtract(used).amount();
    }

    public void block() {
        if (this.status == AccountStatus.CLOSED) throw new BusinessRuleException("Conta já está encerrada");
        if (this.status == AccountStatus.BLOCKED) throw new BusinessRuleException("Conta já está bloqueada");
        this.status = AccountStatus.BLOCKED;
        this.updatedAt = LocalDateTime.now();
    }

    public void unblock() {
        if (this.status != AccountStatus.BLOCKED) throw new BusinessRuleException("Conta não está bloqueada");
        this.status = AccountStatus.ACTIVE;
        this.updatedAt = LocalDateTime.now();
    }

    public void close() {
        if (this.status == AccountStatus.CLOSED) throw new BusinessRuleException("Conta já está encerrada");
        if (!this.balance.isZero()) throw new BusinessRuleException("Não é possível encerrar uma conta com saldo");
        this.status = AccountStatus.CLOSED;
        this.closedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public void updateLimits(Limits newLimits) {
        if (this.status != AccountStatus.ACTIVE) throw new BusinessRuleException("Somente contas ativas podem ter limites alterados");
        this.limits = newLimits;
        this.updatedAt = LocalDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getCustomerId() { return customerId; }
    public String getAccountNumber() { return accountNumber; }
    public String getAccountDigit() { return accountDigit; }
    public String getBranch() { return branch; }
    public AccountType getType() { return type; }
    public Money getBalance() { return balance; }
    public Money getAvailableBalance() { return availableBalance; }
    public Limits getLimits() { return limits; }
    public AccountStatus getStatus() { return status; }
    public LocalDateTime getOpenedAt() { return openedAt; }
    public LocalDateTime getClosedAt() { return closedAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
}