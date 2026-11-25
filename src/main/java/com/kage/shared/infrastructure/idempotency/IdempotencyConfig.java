package com.kage.shared.infrastructure.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * O filtro em si NÃO é bean: o Spring Boot registraria qualquer Filter como filtro de servlet, e ele
 * rodaria duas vezes (e antes da autenticação). Ele entra só na cadeia do Spring Security
 * (SecurityConfig), depois da autorização.
 */
@Configuration
public class IdempotencyConfig {

    private static final Logger logger = LoggerFactory.getLogger(IdempotencyConfig.class);

    @Bean
    public IdempotencyRepository idempotencyRepository(JdbcTemplate jdbcTemplate) {
        return new IdempotencyRepository(jdbcTemplate);
    }

    @Bean
    public ExpiredKeysCleanup expiredIdempotencyKeysCleanup(IdempotencyRepository repository, Clock clock) {
        return new ExpiredKeysCleanup(repository, clock);
    }

    /** Apaga chaves vencidas de hora em hora (elas também são ignoradas na leitura, então isso é só faxina). */
    public static class ExpiredKeysCleanup {
        private final IdempotencyRepository repository;
        private final Clock clock;

        ExpiredKeysCleanup(IdempotencyRepository repository, Clock clock) {
            this.repository = repository;
            this.clock = clock;
        }

        @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
        public void run() {
            int deleted = repository.deleteExpired(LocalDateTime.now(clock));
            if (deleted > 0) logger.info("{} chave(s) de idempotência vencida(s) removida(s)", deleted);
        }
    }
}
