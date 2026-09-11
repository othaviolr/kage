package com.kage;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * Base para os testes de integração: sobe Postgres e RabbitMQ reais via Testcontainers
 * (mesmas imagens do docker-compose.yml do projeto) e substitui as propriedades de conexão
 * do application.yml pelas dos containers efêmeros via @DynamicPropertySource. O Flyway roda
 * normal por cima, criando o schema do zero em cada execução.
 *
 * Virou classe base compartilhada a partir do terceiro teste de integração que precisava do
 * mesmo setup (PixTransferIntegrationTest, OptimisticLockingIntegrationTest,
 * PixMessageIdempotencyIntegrationTest) — regra dos três.
 */
@SpringBootTest
@Testcontainers
public abstract class AbstractIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:15-alpine"))
            .withDatabaseName("kage_db")
            .withUsername("kage")
            .withPassword("kage123");

    @Container
    static final RabbitMQContainer rabbitmq = new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-management-alpine"));

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        registry.add("spring.rabbitmq.host", rabbitmq::getHost);
        registry.add("spring.rabbitmq.port", rabbitmq::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbitmq::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbitmq::getAdminPassword);
    }

    /**
     * Poll simples (sem Awaitility, pra não puxar dependência nova) pra esperar efeitos
     * assíncronos do RabbitMQ (débito/crédito via consumer, confirmação de status etc.)
     * se propagarem antes de assertar.
     */
    protected void awaitUntil(Supplier<Boolean> condition, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (Boolean.TRUE.equals(condition.get())) {
                return;
            }
            sleep(Duration.ofMillis(200));
        }
        throw new AssertionError("Condição não atendida dentro do timeout de " + timeout);
    }

    protected void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
