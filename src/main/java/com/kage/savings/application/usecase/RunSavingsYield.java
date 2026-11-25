package com.kage.savings.application.usecase;

import com.kage.savings.domain.repository.SavingsBoxRepository;
import com.kage.savings.domain.service.YieldPolicy;
import com.kage.shared.domain.valueobject.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Rendimento diário das caixinhas. Rodando no dia {@code today}, rende os dias úteis até ONTEM (o dia
 * corrente ainda não acabou). Cada caixinha na própria transação; uma que conflita com um guardar /
 * resgatar simultâneo (409) é pega na próxima rodada — o rendimento não se perde, porque o cálculo
 * vai do último dia rendido até a data.
 */
public class RunSavingsYield {

    private static final Logger logger = LoggerFactory.getLogger(RunSavingsYield.class);

    public record Output(LocalDate yieldUntil, BigDecimal annualCdi, int boxes, BigDecimal totalCredited, int conflicts) {}

    private final SavingsBoxRepository repository;
    private final SavingsYieldExecutor executor;
    private final BigDecimal annualCdi;

    public RunSavingsYield(SavingsBoxRepository repository, SavingsYieldExecutor executor,
                           BigDecimal annualCdi) {
        this.repository = repository;
        this.executor = executor;
        this.annualCdi = annualCdi;
    }

    public Output execute(LocalDate today) {
        LocalDate upTo = today.minusDays(1);
        BigDecimal dailyRate = YieldPolicy.dailyRate(annualCdi);
        Money total = Money.ZERO;
        int boxes = 0, conflicts = 0;

        for (UUID boxId : repository.findIdsPendingYieldUntil(upTo)) {
            try {
                total = total.add(executor.accrue(boxId, upTo, dailyRate));
                boxes++;
            } catch (OptimisticLockingFailureException e) {
                conflicts++;
                logger.warn("Caixinha {} mudou durante o rendimento; fica para a próxima rodada", boxId);
            }
        }
        logger.info("Rendimento das caixinhas até {} (CDI {} a.a.): {} caixinha(s), R$ {} creditados, {} conflito(s)",
                upTo, annualCdi, boxes, total.amount(), conflicts);
        return new Output(upTo, annualCdi, boxes, total.amount(), conflicts);
    }
}
