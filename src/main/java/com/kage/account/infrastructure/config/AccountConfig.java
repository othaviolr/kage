package com.kage.account.infrastructure.config;

import com.kage.account.application.usecase.*;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.account.infrastructure.persistence.AccountJpaRepository;
import com.kage.account.infrastructure.persistence.AccountRepositoryImpl;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AccountConfig {

    @Bean
    public AccountRepository accountRepository(AccountJpaRepository accountJpaRepository) {
        return new AccountRepositoryImpl(accountJpaRepository);
    }

    @Bean
    public CreateAccount createAccount(AccountRepository accountRepository) {
        return new CreateAccount(accountRepository);
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
    public CloseAccount closeAccount(AccountRepository accountRepository) {
        return new CloseAccount(accountRepository);
    }

    @Bean
    public UpdateLimits updateLimits(AccountRepository accountRepository) {
        return new UpdateLimits(accountRepository);
    }

    @Bean DepositAccount depositAccount(AccountRepository accountRepository) {
        return new DepositAccount(accountRepository);
    }
}