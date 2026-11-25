package com.kage.account.infrastructure.config;

import com.kage.account.application.usecase.*;
import com.kage.account.domain.repository.AccountEntryRepository;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.account.domain.service.AccountCustomerValidationService;
import com.kage.account.domain.service.AccountSavingsCheck;
import com.kage.account.infrastructure.messaging.OutboxAccountEventPublisher;
import com.kage.account.infrastructure.persistence.AccountEntryRepositoryImpl;
import com.kage.account.infrastructure.persistence.AccountJpaRepository;
import com.kage.account.infrastructure.persistence.AccountRepositoryImpl;
import com.kage.customer.domain.repository.CustomerRepository;
import com.kage.customer.infrastructure.CustomerValidationServiceImpl;
import com.kage.shared.infrastructure.messaging.OutboxEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;

@Configuration
public class AccountConfig {

    @Bean
    public AccountRepository accountRepository(AccountJpaRepository accountJpaRepository) {
        return new AccountRepositoryImpl(accountJpaRepository);
    }

    @Bean
    public AccountCustomerValidationService accountCustomerValidationService(CustomerRepository customerRepository) {
        return new CustomerValidationServiceImpl(customerRepository);
    }

    @Bean
    public CreateAccount createAccount(AccountRepository accountRepository,
                                       AccountCustomerValidationService accountCustomerValidationService) {
        return new CreateAccount(accountRepository, accountCustomerValidationService);
    }

    @Bean
    public GetAccount getAccount(AccountRepository accountRepository) {
        return new GetAccount(accountRepository);
    }

    @Bean
    public BlockAccount blockAccount(AccountRepository accountRepository) {
        return new BlockAccount(accountRepository);
    }

    @Bean
    public UnblockAccount unblockAccount(AccountRepository accountRepository) {
        return new UnblockAccount(accountRepository);
    }

    @Bean
    public CloseAccount closeAccount(AccountRepository accountRepository, AccountSavingsCheck accountSavingsCheck) {
        return new CloseAccount(accountRepository, accountSavingsCheck);
    }

    @Bean
    public UpdateLimits updateLimits(AccountRepository accountRepository) {
        return new UpdateLimits(accountRepository);
    }

    @Bean
    public AccountEventPublisher accountEventPublisher(OutboxEventPublisher outboxEventPublisher) {
        return new OutboxAccountEventPublisher(outboxEventPublisher);
    }

    @Bean
    public AccountEntryRepository accountEntryRepository(JdbcTemplate jdbcTemplate) {
        return new AccountEntryRepositoryImpl(jdbcTemplate);
    }

    @Bean
    public DepositAccount depositAccount(AccountRepository accountRepository, AccountEntryRepository accountEntryRepository,
                                         AccountEventPublisher accountEventPublisher) {
        return new DepositAccount(accountRepository, accountEntryRepository, accountEventPublisher);
    }

    @Bean
    public WithdrawAccount withdrawAccount(AccountRepository accountRepository, AccountEntryRepository accountEntryRepository,
                                           AccountEventPublisher accountEventPublisher, Clock clock) {
        return new WithdrawAccount(accountRepository, accountEntryRepository, accountEventPublisher, clock);
    }

    @Bean
    public GetStatement getStatement(AccountRepository accountRepository, AccountEntryRepository accountEntryRepository,
                                     Clock clock) {
        return new GetStatement(accountRepository, accountEntryRepository, clock);
    }
}