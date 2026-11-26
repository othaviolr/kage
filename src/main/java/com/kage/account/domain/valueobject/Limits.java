package com.kage.account.domain.valueobject;

import com.kage.account.domain.enums.LimitType;
import com.kage.shared.domain.valueobject.Money;
import com.kage.shared.domain.exception.DomainException;

public record Limits(Money dailyTransferLimit, Money monthlyTransferLimit, Money pixDailyLimit,
                     Money pixNightLimit, Money dailyWithdrawalLimit)
{
    public Limits {
        if (dailyTransferLimit == null) throw new DomainException("Limite diário de transferência não pode ser nulo");
        if (monthlyTransferLimit == null) throw new DomainException("Limite mensal de transferência não pode ser nulo");
        if (pixDailyLimit == null) throw new DomainException("Limite diário de Pix não pode ser nulo");
        if (pixNightLimit == null) throw new DomainException("Limite noturno de Pix não pode ser nulo");
        if (dailyWithdrawalLimit == null) throw new DomainException("Limite diário de saque não pode ser nulo");
    }

    public static Limits defaultLimits() {
        return new Limits(Money.of("5000.00"), Money.of("20000.00"), Money.of("2000.00"),
                Money.of("1000.00"), Money.of("1000.00"));
    }

    public Money get(LimitType type) {
        return switch (type) {
            case PIX_DAILY -> pixDailyLimit;
            case PIX_NIGHT -> pixNightLimit;
            case DAILY_WITHDRAWAL -> dailyWithdrawalLimit;
            case DAILY_TRANSFER -> dailyTransferLimit;
            case MONTHLY_TRANSFER -> monthlyTransferLimit;
        };
    }

    public Limits with(LimitType type, Money value) {
        return switch (type) {
            case PIX_DAILY -> updatePixDailyLimit(value);
            case PIX_NIGHT -> updatePixNightLimit(value);
            case DAILY_WITHDRAWAL -> updateDailyWithdrawalLimit(value);
            case DAILY_TRANSFER -> updateDailyTransferLimit(value);
            case MONTHLY_TRANSFER -> updateMonthlyTransferLimit(value);
        };
    }

    public Limits updateDailyTransferLimit(Money newLimit) {
        return new Limits(newLimit, this.monthlyTransferLimit, this.pixDailyLimit, this.pixNightLimit, this.dailyWithdrawalLimit);
    }

    public Limits updateMonthlyTransferLimit(Money newLimit) {
        return new Limits(this.dailyTransferLimit, newLimit, this.pixDailyLimit, this.pixNightLimit, this.dailyWithdrawalLimit);
    }

    public Limits updatePixDailyLimit(Money newLimit) {
        return new Limits(this.dailyTransferLimit, this.monthlyTransferLimit, newLimit, this.pixNightLimit, this.dailyWithdrawalLimit);
    }

    public Limits updatePixNightLimit(Money newLimit) {
        return new Limits(this.dailyTransferLimit, this.monthlyTransferLimit, this.pixDailyLimit, newLimit, this.dailyWithdrawalLimit);
    }

    public Limits updateDailyWithdrawalLimit(Money newLimit) {
        return new Limits(this.dailyTransferLimit, this.monthlyTransferLimit, this.pixDailyLimit, this.pixNightLimit, newLimit);
    }
}