package com.kage.savings.domain.service;

import com.kage.savings.domain.valueobject.CdiSchedule;

import java.time.LocalDate;

/** De onde vem o CDI: Banco Central (série SGS 12) ou uma taxa fixa de configuração. */
public interface CdiRateProvider {

    /**
     * Taxas dos dias em (fromExclusive, toInclusive]. O {@code confirmedUntil} pode ficar antes de
     * {@code toInclusive} quando a taxa de algum dia útil ainda não foi publicada.
     *
     * @throws CdiUnavailableException fonte fora do ar — melhor não render hoje do que render errado
     */
    CdiSchedule schedule(LocalDate fromExclusive, LocalDate toInclusive);

    class CdiUnavailableException extends RuntimeException {
        public CdiUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
