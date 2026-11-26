package com.kage.shared.infrastructure.config;

import com.kage.shared.domain.DomainTime;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Relógio injetável: regras que dependem de "hoje" ou do horário (limite diário, janela noturna do
 * PIX) recebem um Clock em vez de chamar LocalDateTime.now(), e os testes fixam o horário. As
 * entidades usam o mesmo relógio pelo {@link DomainTime}, amarrado aqui.
 *
 * O fuso é o da JVM, e o container sobe com -Duser.timezone=America/Sao_Paulo (Dockerfile): as regras
 * do banco são em horário de Brasília (em UTC, o dia do limite diário viraria às 21h).
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }

    /** Qualquer bean Clock (inclusive um de teste) passa a ser o "agora" das entidades. */
    @Bean
    public DomainTimeBinding domainTimeBinding(Clock clock) {
        DomainTime.use(clock);
        return new DomainTimeBinding(clock);
    }

    public record DomainTimeBinding(Clock clock) {}
}
