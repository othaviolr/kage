package com.kage.account.infrastructure.persistence;

import com.kage.account.domain.enums.AccountStatus;
import com.kage.account.domain.enums.AccountType;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class AccountJpaEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "account_number", nullable = false, unique = true, length = 5)
    private String accountNumber;

    @Column(name = "account_digit", nullable = false, length = 1)
    private String accountDigit;

    @Column(nullable = false, length = 4)
    private String branch;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountType type;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @Column(name = "available_balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal availableBalance;

    @Column(name = "daily_transfer_limit", nullable = false, precision = 19, scale = 2)
    private BigDecimal dailyTransferLimit;

    @Column(name = "monthly_transfer_limit", nullable = false, precision = 19, scale = 2)
    private BigDecimal monthlyTransferLimit;

    @Column(name = "pix_daily_limit", nullable = false, precision = 19, scale = 2)
    private BigDecimal pixDailyLimit;

    @Column(name = "pix_night_limit", nullable = false, precision = 19, scale = 2)
    private BigDecimal pixNightLimit;

    @Column(name = "daily_withdrawal_limit", nullable = false)
    private BigDecimal dailyWithdrawalLimit;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountStatus status;

    @Column(name = "opened_at", nullable = false)
    private LocalDateTime openedAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    public AccountJpaEntity() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getCustomerId() { return customerId; }
    public void setCustomerId(UUID customerId) { this.customerId = customerId; }
    public String getAccountNumber() { return accountNumber; }
    public void setAccountNumber(String accountNumber) { this.accountNumber = accountNumber; }
    public String getAccountDigit() { return accountDigit; }
    public void setAccountDigit(String accountDigit) { this.accountDigit = accountDigit; }
    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }
    public AccountType getType() { return type; }
    public void setType(AccountType type) { this.type = type; }
    public BigDecimal getBalance() { return balance; }
    public void setBalance(BigDecimal balance) { this.balance = balance; }
    public BigDecimal getAvailableBalance() { return availableBalance; }
    public void setAvailableBalance(BigDecimal availableBalance) { this.availableBalance = availableBalance; }
    public BigDecimal getDailyTransferLimit() { return dailyTransferLimit; }
    public void setDailyTransferLimit(BigDecimal dailyTransferLimit) { this.dailyTransferLimit = dailyTransferLimit; }
    public BigDecimal getMonthlyTransferLimit() { return monthlyTransferLimit; }
    public void setMonthlyTransferLimit(BigDecimal monthlyTransferLimit) { this.monthlyTransferLimit = monthlyTransferLimit; }
    public BigDecimal getPixDailyLimit() { return pixDailyLimit; }
    public void setPixDailyLimit(BigDecimal pixDailyLimit) { this.pixDailyLimit = pixDailyLimit; }
    public BigDecimal getPixNightLimit() { return pixNightLimit; }
    public void setPixNightLimit(BigDecimal pixNightLimit) { this.pixNightLimit = pixNightLimit; }
    public AccountStatus getStatus() { return status; }
    public void setStatus(AccountStatus status) { this.status = status; }
    public LocalDateTime getOpenedAt() { return openedAt; }
    public void setOpenedAt(LocalDateTime openedAt) { this.openedAt = openedAt; }
    public LocalDateTime getClosedAt() { return closedAt; }
    public void setClosedAt(LocalDateTime closedAt) { this.closedAt = closedAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public BigDecimal getDailyWithdrawalLimit() { return dailyWithdrawalLimit; }
    public void setDailyWithdrawalLimit(BigDecimal dailyWithdrawalLimit) { this.dailyWithdrawalLimit = dailyWithdrawalLimit; }
    public Long getVersion() { return version; } void setVersion(Long version) { this.version = version; }
}