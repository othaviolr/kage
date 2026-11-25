package com.kage;

import org.junit.jupiter.api.Test;

/**
 * Sobe o contexto inteiro contra Postgres/RabbitMQ dos Testcontainers (antes dependia de um
 * Postgres local em localhost:5432 com a senha do docker-compose).
 */
class ApplicationTests extends AbstractIntegrationTest {

	@Test
	void contextLoads() {
	}

}
