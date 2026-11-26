package com.kage.loan.infrastructure.config;

import com.kage.account.domain.repository.AccountEntryRepository;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.account.infrastructure.LoanAccountGatewayImpl;
import com.kage.loan.application.usecase.LoanEventPublisher;
import com.kage.loan.application.usecase.PersonalLoans;
import com.kage.loan.application.usecase.RunLoanDebits;
import com.kage.loan.domain.repository.LoanRepository;
import com.kage.loan.domain.service.LoanAccountGateway;
import com.kage.loan.infrastructure.messaging.LoanEvents;
import com.kage.loan.infrastructure.messaging.OutboxLoanEventPublisher;
import com.kage.loan.infrastructure.persistence.JdbcLoanRepository;
import com.kage.shared.infrastructure.messaging.OutboxEventPublisher;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;

@Configuration
public class LoanConfig {

    @Bean
    public LoanRepository loanRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcLoanRepository(jdbcTemplate);
    }

    @Bean
    public LoanAccountGateway loanAccountGateway(AccountRepository accountRepository, AccountEntryRepository accountEntryRepository) {
        return new LoanAccountGatewayImpl(accountRepository, accountEntryRepository);
    }

    @Bean
    public LoanEventPublisher loanEventPublisher(OutboxEventPublisher outboxEventPublisher) {
        return new OutboxLoanEventPublisher(outboxEventPublisher);
    }

    @Bean
    public TopicExchange loanExchange() {
        return new TopicExchange(LoanEvents.EXCHANGE);
    }

    @Bean
    public PersonalLoans personalLoans(LoanRepository loanRepository, LoanAccountGateway loanAccountGateway,
                                       LoanEventPublisher loanEventPublisher) {
        return new PersonalLoans(loanRepository, loanAccountGateway, loanEventPublisher);
    }

    @Bean
    public RunLoanDebits runLoanDebits(LoanRepository loanRepository, LoanAccountGateway loanAccountGateway,
                                       PlatformTransactionManager transactionManager, LoanEventPublisher loanEventPublisher) {
        return new RunLoanDebits(loanRepository, loanAccountGateway, new TransactionTemplate(transactionManager),
                loanEventPublisher);
    }

    /** Débito automático das parcelas: todo dia de manhã. Os testes desligam (kage.loans.scheduler-enabled=false). */
    @Bean
    @ConditionalOnProperty(name = "kage.loans.scheduler-enabled", havingValue = "true", matchIfMissing = true)
    public LoanDebitScheduler loanDebitScheduler(RunLoanDebits runLoanDebits, Clock clock) {
        return new LoanDebitScheduler(runLoanDebits, clock);
    }

    public static class LoanDebitScheduler {

        private final RunLoanDebits runLoanDebits;
        private final Clock clock;

        LoanDebitScheduler(RunLoanDebits runLoanDebits, Clock clock) {
            this.runLoanDebits = runLoanDebits;
            this.clock = clock;
        }

        @Scheduled(cron = "${kage.loans.cron:0 30 6 * * *}", zone = "${kage.loans.zone:America/Sao_Paulo}")
        public void run() {
            runLoanDebits.execute(LocalDate.now(clock));
        }
    }
}
