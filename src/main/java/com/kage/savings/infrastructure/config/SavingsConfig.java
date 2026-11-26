package com.kage.savings.infrastructure.config;

import com.kage.account.domain.repository.AccountEntryRepository;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.account.domain.service.AccountSavingsCheck;
import com.kage.account.infrastructure.SavingsAccountGatewayImpl;
import com.kage.savings.application.usecase.GetIncomeReport;
import com.kage.savings.application.usecase.IncomeReportDocuments;
import com.kage.savings.infrastructure.report.PdfIncomeReportRenderer;
import com.kage.savings.application.usecase.RunSavingsYield;
import com.kage.savings.infrastructure.report.JdbcIncomeReportSource;
import com.kage.savings.application.usecase.SavingsBoxes;
import com.kage.savings.application.usecase.SavingsEventPublisher;
import com.kage.savings.application.usecase.SavingsYieldExecutor;
import com.kage.savings.domain.repository.SavingsBoxRepository;
import com.kage.savings.domain.service.SavingsAccountGateway;
import com.kage.savings.domain.service.CdiRateProvider;
import com.kage.savings.infrastructure.AccountSavingsCheckImpl;
import com.kage.savings.infrastructure.cdi.BcbCdiRateProvider;
import com.kage.savings.infrastructure.cdi.FixedCdiRateProvider;
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
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;

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

    /**
     * kage.savings.cdi-source: "bcb" = série real do Banco Central; "fixed" (padrão) = taxa anual de
     * kage.savings.annual-cdi nos dias úteis do calendário nacional — determinístico, e permite simular
     * datas futuras pelo backoffice.
     */
    @Bean
    public CdiRateProvider cdiRateProvider(@Value("${kage.savings.cdi-source:fixed}") String source,
                                           @Value("${kage.savings.annual-cdi:0.1415}") BigDecimal annualCdi,
                                           @Value("${kage.savings.bcb-url:https://api.bcb.gov.br}") String bcbUrl) {
        if ("bcb".equalsIgnoreCase(source)) {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(Duration.ofSeconds(5));
            factory.setReadTimeout(Duration.ofSeconds(15));
            return new BcbCdiRateProvider(RestClient.builder().baseUrl(bcbUrl).requestFactory(factory).build());
        }
        if (!"fixed".equalsIgnoreCase(source)) {
            throw new IllegalStateException("kage.savings.cdi-source deve ser 'bcb' ou 'fixed', não '" + source + "'");
        }
        return new FixedCdiRateProvider(annualCdi);
    }

    @Bean
    public GetIncomeReport getIncomeReport(JdbcTemplate jdbcTemplate, Clock clock) {
        return new GetIncomeReport(new JdbcIncomeReportSource(jdbcTemplate), clock);
    }

    @Bean
    public IncomeReportDocuments incomeReportDocuments(GetIncomeReport getIncomeReport) {
        return new IncomeReportDocuments(getIncomeReport, new PdfIncomeReportRenderer());
    }

    @Bean
    public RunSavingsYield runSavingsYield(SavingsBoxRepository savingsBoxRepository, SavingsYieldExecutor savingsYieldExecutor,
                                           CdiRateProvider cdiRateProvider) {
        return new RunSavingsYield(savingsBoxRepository, savingsYieldExecutor, cdiRateProvider);
    }

    @Bean
    @ConditionalOnProperty(name = "kage.savings.scheduler-enabled", havingValue = "true", matchIfMissing = true)
    public SavingsYieldScheduler savingsYieldScheduler(RunSavingsYield runSavingsYield, Clock clock) {
        return new SavingsYieldScheduler(runSavingsYield, clock);
    }
}
