package com.kage.card.infrastructure.config;

import com.kage.account.domain.repository.AccountEntryRepository;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.account.infrastructure.CardAccountValidationServiceImpl;
import com.kage.account.infrastructure.CardBillingAccountServiceImpl;
import com.kage.card.domain.service.CardBillingAccountService;
import com.kage.card.application.usecase.*;
import com.kage.card.domain.repository.CardRepository;
import com.kage.card.domain.repository.InvoiceRepository;
import com.kage.card.domain.service.CardAccountValidationService;
import com.kage.card.domain.service.CardCustomerValidationService;
import com.kage.card.infrastructure.persistence.*;
import com.kage.customer.domain.repository.CustomerRepository;
import com.kage.customer.infrastructure.CustomerValidationServiceImpl;
import com.kage.card.infrastructure.messaging.CardEvents;
import com.kage.card.infrastructure.messaging.OutboxCardEventPublisher;
import com.kage.card.infrastructure.scheduling.BillingScheduler;
import com.kage.shared.infrastructure.messaging.OutboxEventPublisher;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class CardConfig {

    @Bean
    public CardRepository cardRepository(CardJpaRepository cardJpaRepository) {
        return new CardRepositoryImpl(cardJpaRepository);
    }

    @Bean
    public InvoiceRepository invoiceRepository(InvoiceJpaRepository invoiceJpaRepository,
                                               InvoiceItemJpaRepository invoiceItemJpaRepository) {
        return new InvoiceRepositoryImpl(invoiceJpaRepository, invoiceItemJpaRepository);
    }

    @Bean
    public CardCustomerValidationService cardCustomerValidationService(CustomerRepository customerRepository) {
        return new CustomerValidationServiceImpl(customerRepository);
    }

    @Bean
    public CardAccountValidationService cardAccountValidationService(AccountRepository accountRepository) {
        return new CardAccountValidationServiceImpl(accountRepository);
    }

    @Bean
    public IssueCard issueCard(CardRepository cardRepository,
                               CardCustomerValidationService cardCustomerValidationService,
                               CardAccountValidationService cardAccountValidationService) {
        return new IssueCard(cardRepository, cardCustomerValidationService, cardAccountValidationService);
    }

    @Bean
    public GetCard getCard(CardRepository cardRepository) {
        return new GetCard(cardRepository);
    }

    @Bean
    public BlockCard blockCard(CardRepository cardRepository) {
        return new BlockCard(cardRepository);
    }

    @Bean
    public UnblockCard unblockCard(CardRepository cardRepository) {
        return new UnblockCard(cardRepository);
    }

    @Bean
    public RegisterPurchase registerPurchase(CardRepository cardRepository, InvoiceRepository invoiceRepository) {
        return new RegisterPurchase(cardRepository, invoiceRepository);
    }

    @Bean
    public GetInvoice getInvoice(CardRepository cardRepository, InvoiceRepository invoiceRepository) {
        return new GetInvoice(cardRepository, invoiceRepository);
    }

    @Bean
    public ListInvoices listInvoices(CardRepository cardRepository, InvoiceRepository invoiceRepository) {
        return new ListInvoices(cardRepository, invoiceRepository);
    }

    @Bean
    public CloseInvoice closeInvoice(CardRepository cardRepository, InvoiceRepository invoiceRepository,
                                     CardEventPublisher cardEventPublisher) {
        return new CloseInvoice(cardRepository, invoiceRepository, cardEventPublisher);
    }

    @Bean
    public CardEventPublisher cardEventPublisher(OutboxEventPublisher outboxEventPublisher) {
        return new OutboxCardEventPublisher(outboxEventPublisher);
    }

    @Bean
    public TopicExchange cardExchange() {
        return new TopicExchange(CardEvents.EXCHANGE);
    }

    @Bean
    public CardBillingAccountService cardBillingAccountService(AccountRepository accountRepository,
                                                               AccountEntryRepository accountEntryRepository) {
        return new CardBillingAccountServiceImpl(accountRepository, accountEntryRepository);
    }

    @Bean
    public PayInvoice payInvoice(CardRepository cardRepository, InvoiceRepository invoiceRepository,
                                 CardBillingAccountService cardBillingAccountService,
                                 CardEventPublisher cardEventPublisher) {
        return new PayInvoice(cardRepository, invoiceRepository, cardBillingAccountService, cardEventPublisher);
    }

    @Bean
    public SetAutoDebit setAutoDebit(CardRepository cardRepository) {
        return new SetAutoDebit(cardRepository);
    }

    @Bean
    public RunBillingCycle runBillingCycle(InvoiceRepository invoiceRepository, CardRepository cardRepository,
                                           CloseInvoice closeInvoice, PayInvoice payInvoice,
                                           CardEventPublisher cardEventPublisher) {
        return new RunBillingCycle(invoiceRepository, cardRepository, closeInvoice, payInvoice, cardEventPublisher);
    }

    @Bean
    @ConditionalOnProperty(name = "kage.billing.scheduler-enabled", havingValue = "true", matchIfMissing = true)
    public BillingScheduler billingScheduler(RunBillingCycle runBillingCycle, Clock clock) {
        return new BillingScheduler(runBillingCycle, clock);
    }

    @Bean
    public CancelCard cancelCard(CardRepository cardRepository) {
        return new CancelCard(cardRepository);
    }
}
