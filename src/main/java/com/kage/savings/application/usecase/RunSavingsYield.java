package com.kage.savings.application.usecase;

import com.kage.savings.domain.repository.SavingsBoxRepository;
import com.kage.savings.domain.service.CdiRateProvider;
import com.kage.savings.domain.valueobject.CdiSchedule;
import com.kage.shared.domain.valueobject.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Rendimento diário das caixinhas. Rodando no dia {@code today}, rende até ONTEM (o dia corrente
 * ainda não acabou) — ou até onde o CDI já foi publicado, se for antes.
 *
 * O CDI é buscado uma vez para o período inteiro. Cada caixinha rende na própria transação; uma que
 * conflita com um guardar/resgatar simultâneo (409) é pega na próxima rodada — e nada se perde,
 * porque o cálculo vai do último dia rendido até a data. Com a fonte do CDI fora do ar, não rende
 * nada hoje (melhor atrasar do que inventar taxa): amanhã o período inteiro é recuperado.
 */
public class RunSavingsYield {

    private static final Logger logger = LoggerFactory.getLogger(RunSavingsYield.class);

    public record Output(LocalDate requestedUntil, LocalDate yieldUntil, String cdiSource, int boxes,
                         BigDecimal totalCredited, int conflicts, String error) {}

    private final SavingsBoxRepository repository;
    private final SavingsYieldExecutor executor;
    private final CdiRateProvider cdiRateProvider;

    public RunSavingsYield(SavingsBoxRepository repository, SavingsYieldExecutor executor, CdiRateProvider cdiRateProvider) {
        this.repository = repository;
        this.executor = executor;
        this.cdiRateProvider = cdiRateProvider;
    }

    public Output execute(LocalDate today) {
        LocalDate upTo = today.minusDays(1);
        Optional<LocalDate> oldest = repository.oldestYieldDateBefore(upTo);
        if (oldest.isEmpty()) return new Output(upTo, upTo, null, 0, BigDecimal.ZERO.setScale(2), 0, null);

        CdiSchedule cdi;
        try {
            cdi = cdiRateProvider.schedule(oldest.get(), upTo);
        } catch (CdiRateProvider.CdiUnavailableException e) {
            logger.error("CDI indisponível; rendimento das caixinhas fica para a próxima rodada: {}", e.getMessage());
            return new Output(upTo, null, null, 0, BigDecimal.ZERO.setScale(2), 0, e.getMessage());
        }

        Money total = Money.ZERO;
        int boxes = 0, conflicts = 0;
        for (UUID boxId : repository.findIdsPendingYieldUntil(cdi.confirmedUntil().plusDays(1))) {
            try {
                total = total.add(executor.accrue(boxId, cdi));
                boxes++;
            } catch (OptimisticLockingFailureException e) {
                conflicts++;
                logger.warn("Caixinha {} mudou durante o rendimento; fica para a próxima rodada", boxId);
            }
        }
        logger.info("Rendimento das caixinhas até {} (CDI: {}): {} caixinha(s), R$ {} creditados, {} conflito(s)",
                cdi.confirmedUntil(), cdi.source(), boxes, total.amount(), conflicts);
        return new Output(upTo, cdi.confirmedUntil(), cdi.source(), boxes, total.amount(), conflicts, null);
    }
}
