package com.kage.boleto.infrastructure.config;

import com.kage.account.domain.repository.AccountEntryRepository;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.account.infrastructure.BoletoAccountGatewayImpl;
import com.kage.boleto.application.usecase.BoletoEventPublisher;
import com.kage.boleto.application.usecase.BoletoReceipts;
import com.kage.boleto.application.usecase.IssueBoletos;
import com.kage.boleto.application.usecase.PayBoletos;
import com.kage.boleto.domain.repository.BoletoPaymentRepository;
import com.kage.boleto.domain.repository.BoletoRepository;
import com.kage.boleto.domain.service.BoletoAccountGateway;
import com.kage.boleto.infrastructure.messaging.BoletoEvents;
import com.kage.boleto.infrastructure.messaging.OutboxBoletoEventPublisher;
import com.kage.boleto.infrastructure.persistence.JdbcBoletoPaymentRepository;
import com.kage.boleto.infrastructure.persistence.JdbcBoletoRepository;
import com.kage.customer.domain.repository.CustomerRepository;
import com.kage.shared.infrastructure.messaging.OutboxEventPublisher;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;

@Configuration
public class BoletoConfig {

    /** Código de compensação do Kage nos boletos que emite (fictício: projeto de estudo). */
    @Value("${kage.boleto.bank-code:999}")
    private String bankCode;

    @Bean
    public BoletoRepository boletoRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcBoletoRepository(jdbcTemplate);
    }

    @Bean
    public BoletoPaymentRepository boletoPaymentRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcBoletoPaymentRepository(jdbcTemplate);
    }

    @Bean
    public BoletoAccountGateway boletoAccountGateway(AccountRepository accountRepository,
                                                     AccountEntryRepository accountEntryRepository,
                                                     CustomerRepository customerRepository) {
        return new BoletoAccountGatewayImpl(accountRepository, accountEntryRepository, customerRepository);
    }

    @Bean
    public BoletoEventPublisher boletoEventPublisher(OutboxEventPublisher outboxEventPublisher) {
        return new OutboxBoletoEventPublisher(outboxEventPublisher);
    }

    @Bean
    public TopicExchange boletoExchange() {
        return new TopicExchange(BoletoEvents.EXCHANGE);
    }

    @Bean
    public IssueBoletos issueBoletos(BoletoRepository boletoRepository, BoletoAccountGateway boletoAccountGateway, Clock clock) {
        return new IssueBoletos(boletoRepository, boletoAccountGateway, bankCode, clock);
    }

    @Bean
    public PayBoletos payBoletos(BoletoRepository boletoRepository, BoletoPaymentRepository boletoPaymentRepository,
                                 BoletoAccountGateway boletoAccountGateway, BoletoEventPublisher boletoEventPublisher,
                                 Clock clock) {
        return new PayBoletos(boletoRepository, boletoPaymentRepository, boletoAccountGateway, boletoEventPublisher,
                bankCode, clock);
    }

    @Bean
    public BoletoReceipts boletoReceipts(BoletoPaymentRepository boletoPaymentRepository, BoletoRepository boletoRepository,
                                         BoletoAccountGateway boletoAccountGateway) {
        return new BoletoReceipts(boletoPaymentRepository, boletoRepository, boletoAccountGateway);
    }
}
