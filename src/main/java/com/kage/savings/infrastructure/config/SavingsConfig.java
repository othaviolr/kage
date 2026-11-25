package com.kage.savings.infrastructure.config;

import com.kage.account.domain.repository.AccountEntryRepository;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.account.domain.service.AccountSavingsCheck;
import com.kage.account.infrastructure.SavingsAccountGatewayImpl;
import com.kage.savings.application.usecase.RunSavingsYield;
import com.kage.savings.application.usecase.SavingsBoxes;
import com.kage.savings.application.usecase.SavingsEventPublisher;
import com.kage.savings.application.usecase.SavingsYieldExecutor;
import com.kage.savings.domain.repository.SavingsBoxRepository;
import com.kage.savings.domain.service.SavingsAccountGateway;
import com.kage.savings.infrastructure.AccountSavingsCheckImpl;
import com.kage.savings.infrastructure.messaging.OutboxSavingsEventPublisher;
import com.kage.savings.infrastructure.messaging.SavingsEvents;
import com.kage.savings.infrastructure.persistence.SavingsBoxRepositoryImpl;
import com.kage.savings.infrastructure.scheduling.SavingsYieldScheduler;
import com.kage.shared.infrastructure.messaging.OutboxEventPublisher;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Clock;

@Configuration
public class SavingsConfig {

    @Bean
    public SavingsBoxRepository savingsBoxRepository(JdbcTemplate jdbcTemplate) {
        return new SavingsBoxRepositoryImpl(jdbcTemplate);
    }

    @Bean
    public SavingsAccountGateway savingsAccountGateway(AccountRepository accountRepository,
                                                       AccountEntryRepository accountEntryRepository) {
        return new SavingsAccountGatewayImpl(accountRepository, accountEntryRepository);
    }

    @Bean
    public AccountSavingsCheck accountSavingsCheck(SavingsBoxRepository savingsBoxRepository) {
        return new AccountSavingsCheckImpl(savingsBoxRepository);
    }

    @Bean
    public SavingsEventPublisher savingsEventPublisher(OutboxEventPublisher outboxEventPublisher) {
        return new OutboxSavingsEventPublisher(outboxEventPublisher);
    }

    @Bean
    public TopicExchange savingsExchange() {
        return new TopicExchange(SavingsEvents.EXCHANGE);
    }

    @Bean
    public SavingsBoxes savingsBoxes(SavingsBoxRepository savingsBoxRepository, SavingsAccountGateway savingsAccountGateway,
                                     SavingsEventPublisher savingsEventPublisher, Clock clock) {
        return new SavingsBoxes(savingsBoxRepository, savingsAccountGateway, savingsEventPublisher, clock);
    }

    @Bean
    public SavingsYieldExecutor savingsYieldExecutor(SavingsBoxRepository savingsBoxRepository,
                                                     SavingsEventPublisher savingsEventPublisher, Clock clock) {
        return new SavingsYieldExecutor(savingsBoxRepository, savingsEventPublisher, clock);
    }

    /** CDI anual como fração (0.1415 = 14,15% a.a.). Numa versão real viria do BCB (série SGS 4389). */
    @Bean
    public RunSavingsYield runSavingsYield(SavingsBoxRepository savingsBoxRepository, SavingsYieldExecutor savingsYieldExecutor,
                                           @Value("${kage.savings.annual-cdi:0.1415}") BigDecimal annualCdi) {
        return new RunSavingsYield(savingsBoxRepository, savingsYieldExecutor, annualCdi);
    }

    @Bean
    @ConditionalOnProperty(name = "kage.savings.scheduler-enabled", havingValue = "true", matchIfMissing = true)
    public SavingsYieldScheduler savingsYieldScheduler(RunSavingsYield runSavingsYield, Clock clock) {
        return new SavingsYieldScheduler(runSavingsYield, clock);
    }
}
