package com.kage.shared.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Relógio injetável: regras que dependem de "hoje" ou do horário (limite diário, janela noturna do
 * PIX) recebem um Clock em vez de chamar LocalDateTime.now(), e os testes fixam o horário.
 *
 * O fuso é o da JVM, e o container sobe com -Duser.timezone=America/Sao_Paulo (Dockerfile): o relógio
 * e os LocalDateTime.now() das entidades precisam concordar, e as regras do banco são em horário de
 * Brasília (em UTC, o dia do limite diário viraria às 21h).
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
