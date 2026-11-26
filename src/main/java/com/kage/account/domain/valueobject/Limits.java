package com.kage.account.domain.valueobject;

import com.kage.account.domain.enums.LimitType;
import com.kage.shared.domain.valueobject.Money;
import com.kage.shared.domain.exception.DomainException;

import java.math.BigDecimal;

/**
 * Limites da conta. {@code pixNightStartHour} é a hora em que começa a janela noturna do PIX (20 a 22,
 * Resolução BCB nº 142); nas operações genéricas por {@link LimitType} ela viaja como número inteiro.
 */
public record Limits(Money dailyTransferLimit, Money monthlyTransferLimit, Money pixDailyLimit,
                     Money pixNightLimit, Money dailyWithdrawalLimit, int pixNightStartHour)
{
    public static final int DEFAULT_NIGHT_START_HOUR = 20;

    public Limits {
        if (dailyTransferLimit == null) throw new DomainException("Limite diário de transferência não pode ser nulo");
        if (monthlyTransferLimit == null) throw new DomainException("Limite mensal de transferência não pode ser nulo");
        if (pixDailyLimit == null) throw new DomainException("Limite diário de Pix não pode ser nulo");
        if (pixNightLimit == null) throw new DomainException("Limite noturno de Pix não pode ser nulo");
        if (dailyWithdrawalLimit == null) throw new DomainException("Limite diário de saque não pode ser nulo");
        if (pixNightStartHour < 20 || pixNightStartHour > 22) {
            throw new DomainException("A janela noturna do PIX começa entre 20h e 22h");
        }
    }

    public Limits(Money dailyTransferLimit, Money monthlyTransferLimit, Money pixDailyLimit, Money pixNightLimit,
                  Money dailyWithdrawalLimit) {
        this(dailyTransferLimit, monthlyTransferLimit, pixDailyLimit, pixNightLimit, dailyWithdrawalLimit,
                DEFAULT_NIGHT_START_HOUR);
    }

    public static Limits defaultLimits() {
        return new Limits(Money.of("5000.00"), Money.of("20000.00"), Money.of("2000.00"),
                Money.of("1000.00"), Money.of("1000.00"), DEFAULT_NIGHT_START_HOUR);
    }

    public Money get(LimitType type) {
        return switch (type) {
            case PIX_DAILY -> pixDailyLimit;
            case PIX_NIGHT -> pixNightLimit;
            case DAILY_WITHDRAWAL -> dailyWithdrawalLimit;
            case DAILY_TRANSFER -> dailyTransferLimit;
            case MONTHLY_TRANSFER -> monthlyTransferLimit;
            case PIX_NIGHT_START -> Money.of(BigDecimal.valueOf(pixNightStartHour));
        };
    }

    public Limits with(LimitType type, Money value) {
        return switch (type) {
            case PIX_DAILY -> updatePixDailyLimit(value);
            case PIX_NIGHT -> updatePixNightLimit(value);
            case DAILY_WITHDRAWAL -> updateDailyWithdrawalLimit(value);
            case DAILY_TRANSFER -> updateDailyTransferLimit(value);
            case MONTHLY_TRANSFER -> updateMonthlyTransferLimit(value);
            case PIX_NIGHT_START -> new Limits(dailyTransferLimit, monthlyTransferLimit, pixDailyLimit, pixNightLimit,
                    dailyWithdrawalLimit, value.amount().intValueExact());
        };
    }

    public Limits updateDailyTransferLimit(Money newLimit) {
        return new Limits(newLimit, this.monthlyTransferLimit, this.pixDailyLimit, this.pixNightLimit, this.dailyWithdrawalLimit,
                this.pixNightStartHour);
    }

    public Limits updateMonthlyTransferLimit(Money newLimit) {
        return new Limits(this.dailyTransferLimit, newLimit, this.pixDailyLimit, this.pixNightLimit, this.dailyWithdrawalLimit,
                this.pixNightStartHour);
    }

    public Limits updatePixDailyLimit(Money newLimit) {
        return new Limits(this.dailyTransferLimit, this.monthlyTransferLimit, newLimit, this.pixNightLimit, this.dailyWithdrawalLimit,
                this.pixNightStartHour);
    }

    public Limits updatePixNightLimit(Money newLimit) {
        return new Limits(this.dailyTransferLimit, this.monthlyTransferLimit, this.pixDailyLimit, newLimit, this.dailyWithdrawalLimit,
                this.pixNightStartHour);
    }

    public Limits updateDailyWithdrawalLimit(Money newLimit) {
        return new Limits(this.dailyTransferLimit, this.monthlyTransferLimit, this.pixDailyLimit, this.pixNightLimit, newLimit,
                this.pixNightStartHour);
    }
}
