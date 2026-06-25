package com.kage.payment.domain.service;

import com.kage.shared.domain.valueobject.Money;

import java.util.UUID;

public interface AccountValidationService {
    void validateBalanceAndLimits(UUID accountId, Money amount);
}