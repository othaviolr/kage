package com.kage.account.domain.valueobject;

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