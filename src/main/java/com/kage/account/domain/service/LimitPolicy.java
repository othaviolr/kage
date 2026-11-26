package com.kage.account.domain.service;

import com.kage.account.domain.enums.LimitType;
import com.kage.account.domain.valueobject.Limits;
import com.kage.shared.domain.exception.ValidationException;
import com.kage.shared.domain.valueobject.Money;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/**
 * Regra de alteração de limites (Resolução BCB nº 142, para o PIX; aqui, para todos os limites da
 * conta):
 * - diminuir vale na hora — o cliente se protege sem esperar;
 * - aumentar só vale depois de {@link #INCREASE_DELAY}: quem pegou o celular desbloqueado de alguém
 *   não consegue subir o limite e esvaziar a conta na mesma noite, e o dono tem tempo de cancelar;
 * - cada limite tem um teto, e o noturno do PIX nunca passa do diurno.
 */
public final class LimitPolicy {

    public static final Duration INCREASE_DELAY = Duration.ofHours(24);

    /** @param immediate limites que valem já; @param increases aumentos que viram pedido */
    public record Plan(Limits immediate, Map<LimitType, Money> increases) {}

    private LimitPolicy() {
    }

    public static Plan plan(Limits current, Map<LimitType, Money> requested) {
        Limits immediate = current;
        Map<LimitType, Money> increases = new EnumMap<>(LimitType.class);
        for (Map.Entry<LimitType, Money> entry : requested.entrySet()) {
            LimitType type = entry.getKey();
            Money value = entry.getValue();
            if (value == null) continue;
            if (value.isGreaterThan(type.ceiling())) {
                throw new ValidationException("O " + type.label() + " pode ser de até " + type.ceiling().amount());
            }
            if (value.isGreaterThan(current.get(type))) {
                increases.put(type, value);
            } else {
                immediate = immediate.with(type, value);
            }
        }

        Money nightNow = immediate.pixNightLimit();
        Money dailyNow = immediate.pixDailyLimit();
        Money nightLater = increases.getOrDefault(LimitType.PIX_NIGHT, nightNow);
        Money dailyLater = increases.getOrDefault(LimitType.PIX_DAILY, dailyNow);
        if (nightNow.isGreaterThan(dailyNow) || nightLater.isGreaterThan(dailyLater)) {
            throw new ValidationException("O limite noturno de PIX não pode ser maior que o diário");
        }
        return new Plan(immediate, increases);
    }

    /**
     * Aplica um aumento que chegou a hora. Se nesse meio-tempo o diário foi reduzido, o noturno para
     * no diário (a regra noturno ≤ diário continua valendo).
     */
    public static Limits applyIncrease(Limits limits, LimitType type, Money value) {
        Limits applied = limits.with(type, value);
        if (applied.pixNightLimit().isGreaterThan(applied.pixDailyLimit())) {
            applied = applied.with(LimitType.PIX_NIGHT, applied.pixDailyLimit());
        }
        return applied;
    }
}
