package com.kage.shared.domain;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * O "agora" do domínio. Entidades marcam a hora das coisas (PIX processado, fatura paga, conta
 * encerrada) sem receber um Clock em cada método, e mesmo assim seguem o relógio da aplicação: o
 * ClockConfig amarra o bean Clock aqui na subida.
 *
 * Por que importa: o limite diário e o noturno do PIX somam o {@code processedAt} das transações
 * comparando com o dia do Clock. Se a entidade usasse o relógio do sistema e o use case o injetado,
 * um teste com o relógio às 22h (ou um ambiente com outro fuso) somaria errado.
 *
 * Testes que fixam o horário chamam {@link #use(Clock)} e {@link #reset()} no fim.
 */
public final class DomainTime {

    private static volatile Clock clock = Clock.systemDefaultZone();

    private DomainTime() {
    }

    public static void use(Clock newClock) {
        clock = Objects.requireNonNull(newClock, "clock");
    }

    public static void reset() {
        clock = Clock.systemDefaultZone();
    }

    public static Clock clock() {
        return clock;
    }

    public static LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    public static LocalDate today() {
        return LocalDate.now(clock);
    }

    public static Instant instant() {
        return clock.instant();
    }
}
