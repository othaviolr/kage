package com.kage;

import com.kage.account.application.usecase.CreateAccount;
import com.kage.card.application.usecase.IssueCard;
import com.kage.customer.application.usecase.approvekyc.ApproveKycInput;
import com.kage.customer.application.usecase.approvekyc.ApproveKycUseCase;
import com.kage.customer.application.usecase.createcustomer.CreateCustomerInput;
import com.kage.customer.application.usecase.createcustomer.CreateCustomerUseCase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
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

    @Autowired
    private CreateCustomerUseCase fixtureCreateCustomer;

    @Autowired
    private ApproveKycUseCase fixtureApproveKyc;

    @Autowired
    private CreateAccount fixtureCreateAccount;

    /** Cria um cliente novo (CPF/e-mail únicos) sem aprovar o KYC: nasce INACTIVE, ainda não elegível. */
    protected UUID newPendingCustomerId() {
        return fixtureCreateCustomer.execute(new CreateCustomerInput(
                "Cliente de Teste", randomCpf(), "cliente-" + UUID.randomUUID() + "@teste.com", "41999991234",
                LocalDate.of(1990, 1, 1), "Rua das Flores", "100", null, "Curitiba", "PR", "80000-000")).id();
    }

    /** Cria um cliente novo, aprova o KYC e devolve o id: nasce ACTIVE. */
    protected UUID newActiveCustomerId() {
        UUID customerId = newPendingCustomerId();
        fixtureApproveKyc.execute(new ApproveKycInput(customerId));
        return customerId;
    }

    /** Abre uma conta corrente para o cliente informado e devolve o id da conta. */
    protected UUID newAccountIdFor(UUID customerId) {
        return fixtureCreateAccount.execute(new CreateAccount.Input(customerId, "CHECKING")).accountId();
    }

    /** Entrada válida pra emitir cartão: cliente ativo novo + conta dele. */
    protected IssueCard.Input issueCardInput(BigDecimal creditLimit, int closingDay, int dueDay) {
        UUID customerId = newActiveCustomerId();
        UUID accountId = newAccountIdFor(customerId);
        return new IssueCard.Input(customerId, accountId, creditLimit, closingDay, dueDay);
    }

    private static String randomCpf() {
        int[] digits = new int[11];
        do {
            for (int i = 0; i < 9; i++) digits[i] = ThreadLocalRandom.current().nextInt(10);
        } while (allEqual(digits, 9));
        digits[9] = checkDigit(digits, 9);
        digits[10] = checkDigit(digits, 10);
        StringBuilder cpf = new StringBuilder();
        for (int digit : digits) cpf.append(digit);
        return cpf.toString();
    }

    private static boolean allEqual(int[] digits, int length) {
        for (int i = 1; i < length; i++) if (digits[i] != digits[0]) return false;
        return true;
    }

    private static int checkDigit(int[] digits, int length) {
        int sum = 0;
        for (int i = 0; i < length; i++) sum += digits[i] * (length + 1 - i);
        int result = 11 - (sum % 11);
        return result >= 10 ? 0 : result;
    }
}
