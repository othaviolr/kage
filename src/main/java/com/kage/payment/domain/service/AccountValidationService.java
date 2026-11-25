package com.kage.payment.domain.service;

import com.kage.shared.domain.valueobject.Money;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Porta do Payment para o Account: a conta de origem pode enviar este PIX?
 *
 * A implementação trava a conta (SELECT ... FOR UPDATE) até o fim da transação do envio e SÓ ENTÃO
 * pede o uso do limite: assim dois PIX simultâneos da mesma conta são serializados, e o segundo já
 * enxerga o primeiro na soma. Por isso o uso chega como Supplier, e não como valor pronto.
 */
public interface AccountValidationService {

    /**
     * @throws com.kage.shared.domain.exception.NotFoundException     se a conta não existe
     * @throws com.kage.shared.domain.exception.BusinessRuleException se a conta não está ativa, não tem saldo
     *                                                                ou o envio estoura um limite
     */
    void validateBalanceAndLimits(UUID accountId, Money amount, Supplier<PixLimitUsage> usage);
}
