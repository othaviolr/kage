package com.kage.payment.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.account.infrastructure.AccountValidationServiceImpl;
import com.kage.payment.application.usecase.*;
import com.kage.payment.domain.repository.PixKeyRepository;
import com.kage.payment.domain.repository.PixRefundRepository;
import com.kage.payment.domain.repository.PixTransactionRepository;
import com.kage.payment.domain.service.AccountValidationService;
import com.kage.payment.infrastructure.controller.PaymentController;
import com.kage.payment.infrastructure.messaging.OutboxPixEventPublisher;
import com.kage.payment.infrastructure.messaging.PixEventConsumer;
import com.kage.payment.infrastructure.messaging.PixOutboxPublisher;
import com.kage.payment.infrastructure.persistence.*;
import com.kage.payment.infrastructure.persistence.idempotency.ProcessedEventRepository;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

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
    public PixEventPublisher pixEventPublisher(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        return new OutboxPixEventPublisher(jdbcTemplate, objectMapper);
    }

    @Bean
    public SendPix sendPix(PixKeyRepository pixKeyRepository,
                           PixTransactionRepository pixTransactionRepository,
                           AccountValidationService accountValidationService,
                           PixEventPublisher pixEventPublisher) {
        return new SendPix(pixKeyRepository, pixTransactionRepository, accountValidationService, pixEventPublisher);
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
    public ApprovePixRefund approvePixRefund(PixRefundRepository pixRefundRepository) {
        return new ApprovePixRefund(pixRefundRepository);
    }

    @Bean
    public RejectPixRefund rejectPixRefund(PixRefundRepository pixRefundRepository) {
        return new RejectPixRefund(pixRefundRepository);
    }

    @Bean
    public PixEventConsumer pixEventConsumer(PixTransactionRepository pixTransactionRepository,
                                             ProcessedEventRepository paymentProcessedEventRepository) {
        return new PixEventConsumer(pixTransactionRepository, paymentProcessedEventRepository);
    }

    @Bean
    public ProcessedEventRepository paymentProcessedEventRepository(JdbcTemplate jdbcTemplate) {
        return new ProcessedEventRepository(jdbcTemplate);
    }

    @Bean
    public PixOutboxPublisher pixOutboxPublisher(JdbcTemplate jdbcTemplate, RabbitTemplate rabbitTemplate, ObjectMapper objectMapper) {
        return new PixOutboxPublisher(jdbcTemplate, rabbitTemplate, objectMapper);
    }

    @Bean
    public PaymentController paymentController(RegisterPixKey registerPixKey,
                                               DeletePixKey deletePixKey,
                                               GetPixKey getPixKey,
                                               SendPix sendPix,
                                               GetPixTransaction getPixTransaction,
                                               RequestPixRefund requestPixRefund,
                                               ApprovePixRefund approvePixRefund,
                                               RejectPixRefund rejectPixRefund) {
        return new PaymentController(registerPixKey, deletePixKey, getPixKey, sendPix,
                getPixTransaction, requestPixRefund, approvePixRefund, rejectPixRefund);
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}