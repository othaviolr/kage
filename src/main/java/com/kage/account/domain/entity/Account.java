package com.kage.account.domain.entity;

import com.kage.account.domain.enums.AccountStatus;
import com.kage.account.domain.enums.AccountType;
import com.kage.account.domain.valueobject.Limits;
import com.kage.shared.domain.exception.DomainException;
import com.kage.shared.domain.valueobject.Money;

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

    private Account(UUID id, UUID customerId, String accountNumber, String accountDigit,
                    String branch, AccountType type, Money balance, Money availableBalance,
                    Limits limits, AccountStatus status, LocalDateTime openedAt,
                    LocalDateTime closedAt, LocalDateTime updatedAt) {
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
    }

    public static Account create(UUID customerId, AccountType type, String accountNumber, String accountDigit) {
        return new Account(UUID.randomUUID(), customerId, accountNumber, accountDigit, "0001", type, Money.ZERO, Money.ZERO, Limits.defaultLimits(), AccountStatus.ACTIVE, LocalDateTime.now(), null, LocalDateTime.now());
    }

    public static Account reconstitute(UUID id, UUID customerId, String accountNumber, String accountDigit,
                                       String branch, AccountType type, Money balance, Money availableBalance,
                                       Limits limits, AccountStatus status, LocalDateTime openedAt,
                                       LocalDateTime closedAt, LocalDateTime updatedAt) {
        return new Account(id, customerId, accountNumber, accountDigit, branch, type,
                balance, availableBalance, limits, status, openedAt, closedAt, updatedAt);
    }

    public void credit(Money amount) {
        if (this.status == AccountStatus.CLOSED) throw new DomainException("Não é possível creditar em uma conta encerrada");
        this.balance = this.balance.add(amount);
        this.availableBalance = this.availableBalance.add(amount);
        this.updatedAt = LocalDateTime.now();
    }

    public void debit(Money amount) {
        if (this.status != AccountStatus.ACTIVE) throw new DomainException("Conta não está ativa");
        if (amount.isGreaterThan(this.availableBalance)) throw new DomainException("Saldo disponível insuficiente");
        if (amount.isGreaterThan(this.limits.dailyTransferLimit())) throw new DomainException("Valor excede o limite diário de transferência");
        this.balance = this.balance.subtract(amount);
        this.availableBalance = this.availableBalance.subtract(amount);
        this.updatedAt = LocalDateTime.now();
    }

    public void block() {
        if (this.status == AccountStatus.CLOSED) throw new DomainException("Conta já está encerrada");
        if (this.status == AccountStatus.BLOCKED) throw new DomainException("Conta já está bloqueada");
        this.status = AccountStatus.BLOCKED;
        this.updatedAt = LocalDateTime.now();
    }

    public void unblock() {
        if (this.status != AccountStatus.BLOCKED) throw new DomainException("Conta não está bloqueada");
        this.status = AccountStatus.ACTIVE;
        this.updatedAt = LocalDateTime.now();
    }

    public void close() {
        if (this.status == AccountStatus.CLOSED) throw new DomainException("Conta já está encerrada");
        if (!this.balance.isZero()) throw new DomainException("Não é possível encerrar uma conta com saldo");
        this.status = AccountStatus.CLOSED;
        this.closedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public void updateLimits(Limits newLimits) {
        if (this.status != AccountStatus.ACTIVE) throw new DomainException("Somente contas ativas podem ter limites alterados");
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
}