package com.kage.shared.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Relógio injetável: regras que dependem de "hoje" ou do horário (limite diário, janela noturna do
 * PIX) recebem um Clock em vez de chamar LocalDateTime.now(), e os testes fixam o horário.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
