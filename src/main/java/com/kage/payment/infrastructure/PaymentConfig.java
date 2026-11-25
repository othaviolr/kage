package com.kage.payment.infrastructure;

import com.kage.account.domain.repository.AccountRepository;
import com.kage.account.infrastructure.AccountHolderDirectoryImpl;
import com.kage.account.infrastructure.AccountValidationServiceImpl;
import com.kage.customer.domain.repository.CustomerRepository;
import com.kage.payment.domain.repository.PixAutomaticRepository;
import com.kage.payment.domain.service.AccountHolderDirectory;
import com.kage.payment.application.usecase.*;
import com.kage.payment.domain.repository.PixKeyRepository;
import com.kage.payment.domain.repository.PixRecurrenceRepository;
import com.kage.payment.domain.repository.PixRefundRepository;
import com.kage.payment.domain.repository.PixTransactionRepository;
import com.kage.payment.domain.service.AccountValidationService;
import com.kage.payment.infrastructure.messaging.OutboxPixEventPublisher;
import com.kage.payment.infrastructure.messaging.PixEventConsumer;
import com.kage.payment.infrastructure.persistence.*;
import com.kage.payment.infrastructure.persistence.idempotency.ProcessedEventRepository;
import com.kage.shared.infrastructure.messaging.OutboxEventPublisher;
import com.kage.payment.infrastructure.scheduling.ScheduledPixScheduler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;

@Configuration
public class PaymentConfig {

    @Bean
    public PixKeyRepository pixKeyRepository(PixKeyJpaRepository jpaRepository) {
        return new PixKeyRepositoryImpl(jpaRepository);
    }

    @Bean
    public PixTransactionRepository pixTransactionRepository(PixTransactionJpaRepository jpaRepository) {
        return new PixTransactionRepositoryImpl(jpaRepository);
    }

    @Bean
    public PixRefundRepository pixRefundRepository(PixRefundJpaRepository jpaRepository) {
        return new PixRefundRepositoryImpl(jpaRepository);
    }

    @Bean
    public AccountValidationService accountValidationService(AccountRepository accountRepository) {
        return new AccountValidationServiceImpl(accountRepository);
    }

    @Bean
    public RegisterPixKey registerPixKey(PixKeyRepository pixKeyRepository) {
        return new RegisterPixKey(pixKeyRepository);
    }

    @Bean
    public DeletePixKey deletePixKey(PixKeyRepository pixKeyRepository) {
        return new DeletePixKey(pixKeyRepository);
    }

    @Bean
    public GetPixKey getPixKey(PixKeyRepository pixKeyRepository) {
        return new GetPixKey(pixKeyRepository);
    }

    @Bean
    public PixEventPublisher pixEventPublisher(OutboxEventPublisher outboxEventPublisher) {
        return new OutboxPixEventPublisher(outboxEventPublisher);
    }

    @Bean
    public SendPix sendPix(PixKeyRepository pixKeyRepository,
                           PixTransactionRepository pixTransactionRepository,
                           AccountValidationService accountValidationService,
                           PixEventPublisher pixEventPublisher,
                           Clock clock) {
        return new SendPix(pixKeyRepository, pixTransactionRepository, accountValidationService, pixEventPublisher, clock);
    }

    @Bean
    public SchedulePix schedulePix(PixKeyRepository pixKeyRepository, PixTransactionRepository pixTransactionRepository,
                                   Clock clock) {
        return new SchedulePix(pixKeyRepository, pixTransactionRepository, clock);
    }

    @Bean
    public ManageScheduledPix manageScheduledPix(PixTransactionRepository pixTransactionRepository,
                                                 PixRecurrenceChain pixRecurrenceChain) {
        return new ManageScheduledPix(pixTransactionRepository, pixRecurrenceChain);
    }

    @Bean
    public PixRecurrenceRepository pixRecurrenceRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcPixRecurrenceRepository(jdbcTemplate);
    }

    @Bean
    public PixRecurrenceChain pixRecurrenceChain(PixRecurrenceRepository pixRecurrenceRepository,
                                                 PixTransactionRepository pixTransactionRepository, Clock clock) {
        return new PixRecurrenceChain(pixRecurrenceRepository, pixTransactionRepository, clock);
    }

    @Bean
    public RecurringPix recurringPix(PixKeyRepository pixKeyRepository, PixRecurrenceRepository pixRecurrenceRepository,
                                     PixTransactionRepository pixTransactionRepository,
                                     PixRecurrenceChain pixRecurrenceChain, Clock clock) {
        return new RecurringPix(pixKeyRepository, pixRecurrenceRepository, pixTransactionRepository, pixRecurrenceChain,
                clock);
    }

    @Bean
    public PixAutomaticRepository pixAutomaticRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcPixAutomaticRepository(jdbcTemplate);
    }

    @Bean
    public AccountHolderDirectory accountHolderDirectory(AccountRepository accountRepository,
                                                         CustomerRepository customerRepository) {
        return new AccountHolderDirectoryImpl(accountRepository, customerRepository);
    }

    @Bean
    public PixAutomatic pixAutomatic(PixAutomaticRepository pixAutomaticRepository, PixKeyRepository pixKeyRepository,
                                     PixTransactionRepository pixTransactionRepository,
                                     AccountHolderDirectory accountHolderDirectory, PixEventPublisher pixEventPublisher,
                                     Clock clock) {
        return new PixAutomatic(pixAutomaticRepository, pixKeyRepository, pixTransactionRepository, accountHolderDirectory,
                pixEventPublisher, clock);
    }

    @Bean
    public ScheduledPixExecutor scheduledPixExecutor(PixKeyRepository pixKeyRepository,
                                                     PixTransactionRepository pixTransactionRepository,
                                                     AccountValidationService accountValidationService,
                                                     PixEventPublisher pixEventPublisher,
                                                     PixRecurrenceChain pixRecurrenceChain, Clock clock) {
        return new ScheduledPixExecutor(pixKeyRepository, pixTransactionRepository, accountValidationService,
                pixEventPublisher, pixRecurrenceChain, clock);
    }

    @Bean
    public RunScheduledPix runScheduledPix(PixTransactionRepository pixTransactionRepository,
                                           ScheduledPixExecutor scheduledPixExecutor) {
        return new RunScheduledPix(pixTransactionRepository, scheduledPixExecutor);
    }

    @Bean
    @ConditionalOnProperty(name = "kage.pix.scheduler-enabled", havingValue = "true", matchIfMissing = true)
    public ScheduledPixScheduler scheduledPixScheduler(RunScheduledPix runScheduledPix, Clock clock) {
        return new ScheduledPixScheduler(runScheduledPix, clock);
    }

    @Bean
    public GetPixTransaction getPixTransaction(PixTransactionRepository pixTransactionRepository) {
        return new GetPixTransaction(pixTransactionRepository);
    }

    @Bean
    public RequestPixRefund requestPixRefund(PixTransactionRepository pixTransactionRepository,
                                             PixRefundRepository pixRefundRepository) {
        return new RequestPixRefund(pixTransactionRepository, pixRefundRepository);
    }

    @Bean
    public ApprovePixRefund approvePixRefund(PixRefundRepository pixRefundRepository,
                                             PixTransactionRepository pixTransactionRepository,
                                             PixEventPublisher pixEventPublisher) {
        return new ApprovePixRefund(pixRefundRepository, pixTransactionRepository, pixEventPublisher);
    }

    @Bean
    public GetPixRefund getPixRefund(PixRefundRepository pixRefundRepository) {
        return new GetPixRefund(pixRefundRepository);
    }

    @Bean
    public RejectPixRefund rejectPixRefund(PixRefundRepository pixRefundRepository) {
        return new RejectPixRefund(pixRefundRepository);
    }

    @Bean
    public PixEventConsumer pixEventConsumer(PixTransactionRepository pixTransactionRepository,
                                             PixRefundRepository pixRefundRepository,
                                             ProcessedEventRepository paymentProcessedEventRepository) {
        return new PixEventConsumer(pixTransactionRepository, pixRefundRepository, paymentProcessedEventRepository);
    }

    @Bean
    public ProcessedEventRepository paymentProcessedEventRepository(JdbcTemplate jdbcTemplate) {
        return new ProcessedEventRepository(jdbcTemplate);
    }
}