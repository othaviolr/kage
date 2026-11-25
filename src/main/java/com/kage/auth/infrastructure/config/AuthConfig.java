package com.kage.auth.infrastructure.config;

import com.kage.account.domain.repository.AccountRepository;
import com.kage.auth.application.usecase.AdminLogin;
import com.kage.auth.application.usecase.Login;
import com.kage.auth.application.usecase.SignUp;
import com.kage.auth.domain.repository.CredentialRepository;
import com.kage.auth.domain.service.CustomerDirectory;
import com.kage.auth.domain.service.PasswordHasher;
import com.kage.auth.infrastructure.persistence.CredentialRepositoryImpl;
import com.kage.auth.infrastructure.security.AccessGuard;
import com.kage.auth.infrastructure.security.BCryptPasswordHasher;
import com.kage.auth.infrastructure.security.JwtService;
import com.kage.card.domain.repository.CardRepository;
import com.kage.customer.application.usecase.createcustomer.CreateCustomerUseCase;
import com.kage.customer.domain.repository.CustomerRepository;
import com.kage.customer.infrastructure.AuthCustomerDirectoryImpl;
import com.kage.payment.domain.repository.PixKeyRepository;
import com.kage.payment.domain.repository.PixRefundRepository;
import com.kage.payment.domain.repository.PixTransactionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Duration;

@Configuration
public class AuthConfig {

    @Bean
    public PasswordHasher passwordHasher(@Value("${kage.security.bcrypt-strength:12}") int strength) {
        return new BCryptPasswordHasher(strength);
    }

    @Bean
    public JwtService jwtService(@Value("${kage.security.jwt.secret}") String secret,
                                 @Value("${kage.security.jwt.ttl:15m}") Duration ttl,
                                 Clock clock) {
        return new JwtService(secret, ttl, clock);
    }

    @Bean
    public CredentialRepository credentialRepository(JdbcTemplate jdbcTemplate) {
        return new CredentialRepositoryImpl(jdbcTemplate);
    }

    @Bean
    public CustomerDirectory customerDirectory(CreateCustomerUseCase createCustomerUseCase,
                                               CustomerRepository customerRepository) {
        return new AuthCustomerDirectoryImpl(createCustomerUseCase, customerRepository);
    }

    @Bean
    public SignUp signUp(CustomerDirectory customerDirectory, CredentialRepository credentialRepository,
                         PasswordHasher passwordHasher, Clock clock) {
        return new SignUp(customerDirectory, credentialRepository, passwordHasher, clock);
    }

    @Bean
    public Login login(CustomerDirectory customerDirectory, CredentialRepository credentialRepository,
                       PasswordHasher passwordHasher, JwtService jwtService, Clock clock) {
        return new Login(customerDirectory, credentialRepository, passwordHasher, jwtService, clock);
    }

    /** A senha do admin vem em texto da configuração (variável de ambiente) e é hasheada na subida. */
    @Bean
    public AdminLogin adminLogin(@Value("${kage.security.admin.username}") String username,
                                 @Value("${kage.security.admin.password}") String password,
                                 PasswordHasher passwordHasher, JwtService jwtService) {
        return new AdminLogin(username, passwordHasher.hash(password), passwordHasher, jwtService);
    }

    @Bean
    public AccessGuard accessGuard(AccountRepository accountRepository, CardRepository cardRepository,
                                   PixKeyRepository pixKeyRepository, PixTransactionRepository pixTransactionRepository,
                                   PixRefundRepository pixRefundRepository) {
        return new AccessGuard(accountRepository, cardRepository, pixKeyRepository, pixTransactionRepository,
                pixRefundRepository);
    }
}
