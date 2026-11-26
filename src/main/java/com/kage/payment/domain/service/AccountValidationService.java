package com.kage.payment.domain.service;

import com.kage.shared.domain.valueobject.Money;

import java.util.UUID;
import java.util.function.IntFunction;

/**
 * Porta do Payment para o Account: a conta de origem pode enviar este PIX?
 *
 * A implementação trava a conta (SELECT ... FOR UPDATE) até o fim da transação do envio e SÓ ENTÃO
 * pede o uso do limite: assim dois PIX simultâneos da mesma conta são serializados, e o segundo já
 * enxerga o primeiro na soma. Por isso o uso chega como função, e não como valor pronto — e recebe o
 * início da janela noturna da conta (20h a 22h), que só a conta sabe.
 */
public interface AccountValidationService {

    /**
     * @param usage uso do limite dado o início da janela noturna da conta
     * @throws com.kage.shared.domain.exception.NotFoundException     se a conta não existe
     * @throws com.kage.shared.domain.exception.BusinessRuleException se a conta não está ativa, não tem saldo
     *                                                                ou o envio estoura um limite
     */
    void validateBalanceAndLimits(UUID accountId, Money amount, IntFunction<PixLimitUsage> usage);
}
