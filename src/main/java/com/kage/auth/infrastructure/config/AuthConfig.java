package com.kage.auth.infrastructure.config;

import com.kage.boleto.domain.repository.BoletoRepository;
import com.kage.savings.domain.repository.SavingsBoxRepository;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.auth.application.usecase.AdminLogin;
import com.kage.auth.application.usecase.ChangePassword;
import com.kage.auth.application.usecase.ForgotPassword;
import com.kage.auth.application.usecase.Login;
import com.kage.auth.application.usecase.ResetPassword;
import com.kage.auth.domain.repository.PasswordResetRepository;
import com.kage.auth.domain.service.AuthEventPublisher;
import com.kage.auth.domain.service.PasswordResetSender;
import com.kage.auth.infrastructure.messaging.OutboxAuthEventPublisher;
import com.kage.auth.infrastructure.persistence.PasswordResetRepositoryImpl;
import com.kage.auth.infrastructure.security.LoggingPasswordResetSender;
import com.kage.shared.infrastructure.messaging.OutboxEventPublisher;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import com.kage.auth.application.usecase.ManageSessions;
import com.kage.auth.application.usecase.SessionTokens;
import com.kage.auth.domain.repository.SessionRepository;
import com.kage.auth.infrastructure.messaging.AuthCustomerEventConsumer;
import com.kage.auth.infrastructure.persistence.SessionRepositoryImpl;
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
    public SessionRepository sessionRepository(JdbcTemplate jdbcTemplate) {
        return new SessionRepositoryImpl(jdbcTemplate);
    }

    @Bean
    public SessionTokens sessionTokens(SessionRepository sessionRepository, JwtService jwtService,
                                       CustomerDirectory customerDirectory, Clock clock) {
        return new SessionTokens(sessionRepository, jwtService, customerDirectory, clock);
    }

    @Bean
    public ManageSessions manageSessions(SessionRepository sessionRepository, Clock clock) {
        return new ManageSessions(sessionRepository, clock);
    }

    @Bean
    public AuthCustomerEventConsumer authCustomerEventConsumer(ManageSessions manageSessions) {
        return new AuthCustomerEventConsumer(manageSessions);
    }

    @Bean
    public Login login(CustomerDirectory customerDirectory, CredentialRepository credentialRepository,
                       PasswordHasher passwordHasher, SessionTokens sessionTokens, Clock clock) {
        return new Login(customerDirectory, credentialRepository, passwordHasher, sessionTokens, clock);
    }

    /** A senha do admin vem em texto da configuração (variável de ambiente) e é hasheada na subida. */
    @Bean
    public AdminLogin adminLogin(@Value("${kage.security.admin.username}") String username,
                                 @Value("${kage.security.admin.password}") String password,
                                 PasswordHasher passwordHasher, SessionTokens sessionTokens, Clock clock) {
        return new AdminLogin(username, passwordHasher.hash(password), passwordHasher, sessionTokens, clock);
    }

    @Bean
    public PasswordResetRepository passwordResetRepository(JdbcTemplate jdbcTemplate) {
        return new PasswordResetRepositoryImpl(jdbcTemplate);
    }

    /** Troque por um envio real (SES, SendGrid...) declarando outro bean PasswordResetSender. */
    @Bean
    @ConditionalOnMissingBean(PasswordResetSender.class)
    public PasswordResetSender passwordResetSender() {
        return new LoggingPasswordResetSender();
    }

    @Bean
    public AuthEventPublisher authEventPublisher(OutboxEventPublisher outboxEventPublisher) {
        return new OutboxAuthEventPublisher(outboxEventPublisher);
    }

    @Bean
    public TopicExchange authExchange() {
        return new TopicExchange(OutboxAuthEventPublisher.EXCHANGE);
    }

    @Bean
    public ChangePassword changePassword(CredentialRepository credentialRepository, SessionRepository sessionRepository,
                                         CustomerDirectory customerDirectory, PasswordHasher passwordHasher,
                                         AuthEventPublisher authEventPublisher, Clock clock) {
        return new ChangePassword(credentialRepository, sessionRepository, customerDirectory, passwordHasher,
                authEventPublisher, clock);
    }

    @Bean
    public ForgotPassword forgotPassword(CustomerDirectory customerDirectory, PasswordResetRepository passwordResetRepository,
                                         PasswordResetSender passwordResetSender, Clock clock,
                                         @Value("${kage.security.password-reset.expose-token:false}") boolean exposeToken) {
        return new ForgotPassword(customerDirectory, passwordResetRepository, passwordResetSender, clock, exposeToken);
    }

    @Bean
    public ResetPassword resetPassword(PasswordResetRepository passwordResetRepository, CredentialRepository credentialRepository,
                                       SessionRepository sessionRepository, CustomerDirectory customerDirectory,
                                       PasswordHasher passwordHasher, AuthEventPublisher authEventPublisher, Clock clock) {
        return new ResetPassword(passwordResetRepository, credentialRepository, sessionRepository, customerDirectory,
                passwordHasher, authEventPublisher, clock);
    }

    @Bean
    public AccessGuard accessGuard(AccountRepository accountRepository, CardRepository cardRepository,
                                   PixKeyRepository pixKeyRepository, PixTransactionRepository pixTransactionRepository,
                                   PixRefundRepository pixRefundRepository,
                                   SavingsBoxRepository savingsBoxRepository,
                                   BoletoRepository boletoRepository) {
        return new AccessGuard(accountRepository, cardRepository, pixKeyRepository, pixTransactionRepository,
                pixRefundRepository, savingsBoxRepository, boletoRepository);
    }
}
