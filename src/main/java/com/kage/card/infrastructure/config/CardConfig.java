package com.kage.card.infrastructure.config;

import com.kage.account.domain.repository.AccountEntryRepository;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.account.infrastructure.CardAccountValidationServiceImpl;
import com.kage.account.infrastructure.CardBillingAccountServiceImpl;
import com.kage.card.domain.service.CardBillingAccountService;
import com.kage.card.application.usecase.*;
import com.kage.card.domain.repository.CardPlasticRepository;
import com.kage.card.domain.repository.LimitOfferRepository;
import com.kage.card.domain.repository.CardRepository;
import com.kage.card.domain.repository.DisputeRepository;
import com.kage.card.domain.repository.InvoiceRepository;
import com.kage.card.domain.repository.VirtualCardRepository;
import com.kage.card.domain.service.CardNumberService;
import com.kage.card.infrastructure.security.HmacCardNumberService;
import org.springframework.beans.factory.annotation.Value;
import com.kage.card.domain.service.CardAccountValidationService;
import com.kage.card.domain.service.CardCustomerValidationService;
import com.kage.card.infrastructure.persistence.*;
import com.kage.customer.domain.repository.CustomerRepository;
import com.kage.customer.infrastructure.CardHolderDirectoryImpl;
import com.kage.customer.infrastructure.CustomerValidationServiceImpl;
import com.kage.card.domain.service.CardHolderDirectory;
import com.kage.card.infrastructure.export.PdfInvoiceRenderer;
import com.kage.card.infrastructure.messaging.CardEvents;
import com.kage.card.infrastructure.messaging.OutboxCardEventPublisher;
import com.kage.card.infrastructure.scheduling.BillingScheduler;
import com.kage.shared.infrastructure.messaging.OutboxEventPublisher;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

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
                               CardAccountValidationService cardAccountValidationService,
                               CardPlastics cardPlastics) {
        return new IssueCard(cardRepository, cardCustomerValidationService, cardAccountValidationService, cardPlastics);
    }

    @Bean
    public LimitOfferRepository limitOfferRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcLimitOfferRepository(jdbcTemplate);
    }

    @Bean
    public CreditLimits creditLimits(CardRepository cardRepository, InvoiceRepository invoiceRepository,
                                     LimitOfferRepository limitOfferRepository, CardEventPublisher cardEventPublisher,
                                     org.springframework.transaction.PlatformTransactionManager transactionManager) {
        return new CreditLimits(cardRepository, invoiceRepository, limitOfferRepository, cardEventPublisher,
                new org.springframework.transaction.support.TransactionTemplate(transactionManager));
    }

    /** Análise de limite de todos os cartões ativos: todo dia 1º. Os testes desligam. */
    @Bean
    @ConditionalOnProperty(name = "kage.card-limits.scheduler-enabled", havingValue = "true", matchIfMissing = true)
    public CreditLimitReviewScheduler creditLimitReviewScheduler(CreditLimits creditLimits, Clock clock) {
        return new CreditLimitReviewScheduler(creditLimits, clock);
    }

    public static class CreditLimitReviewScheduler {
        private final CreditLimits creditLimits;
        private final Clock clock;

        CreditLimitReviewScheduler(CreditLimits creditLimits, Clock clock) {
            this.creditLimits = creditLimits;
            this.clock = clock;
        }

        @org.springframework.scheduling.annotation.Scheduled(cron = "${kage.card-limits.cron:0 0 9 1 * *}",
                zone = "${kage.card-limits.zone:America/Sao_Paulo}")
        public void run() {
            creditLimits.runReviews(java.time.LocalDate.now(clock));
        }
    }

    @Bean
    public CardHolderDirectory cardHolderDirectory(CustomerRepository customerRepository) {
        return new CardHolderDirectoryImpl(customerRepository);
    }

    @Bean
    public InvoiceDocuments invoiceDocuments(CardRepository cardRepository, InvoiceRepository invoiceRepository,
                                             CardPlasticRepository cardPlasticRepository,
                                             VirtualCardRepository virtualCardRepository,
                                             CardHolderDirectory cardHolderDirectory) {
        return new InvoiceDocuments(cardRepository, invoiceRepository, cardPlasticRepository, virtualCardRepository,
                cardHolderDirectory, new PdfInvoiceRenderer());
    }

    @Bean
    public CardPlasticRepository cardPlasticRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcCardPlasticRepository(jdbcTemplate);
    }

    @Bean
    public CardPlastics cardPlastics(CardRepository cardRepository, CardPlasticRepository cardPlasticRepository,
                                     CardNumberService cardNumberService, CardEventPublisher cardEventPublisher) {
        return new CardPlastics(cardRepository, cardPlasticRepository, cardNumberService, cardEventPublisher);
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
    public RegisterPurchase registerPurchase(CardRepository cardRepository, InvoiceRepository invoiceRepository,
                                             VirtualCards virtualCards, CardPlastics cardPlastics,
                                             CardEventPublisher cardEventPublisher) {
        return new RegisterPurchase(cardRepository, invoiceRepository, virtualCards, cardPlastics, cardEventPublisher);
    }

    @Bean
    public AuthorizePurchase authorizePurchase(RegisterPurchase registerPurchase, VirtualCards virtualCards,
                                               CardEventPublisher cardEventPublisher) {
        return new AuthorizePurchase(registerPurchase, virtualCards, cardEventPublisher);
    }

    @Bean
    public CardNumberService cardNumberService(@Value("${kage.cards.secret}") String secret,
                                               @Value("${kage.cards.bin:529999}") String bin) {
        return new HmacCardNumberService(secret, bin);
    }

    @Bean
    public VirtualCardRepository virtualCardRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcVirtualCardRepository(jdbcTemplate);
    }

    @Bean
    public VirtualCards virtualCards(CardRepository cardRepository, VirtualCardRepository virtualCardRepository,
                                     CardNumberService cardNumberService) {
        return new VirtualCards(cardRepository, virtualCardRepository, cardNumberService);
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
                                     CardEventPublisher cardEventPublisher, FinanceInvoice financeInvoice) {
        return new CloseInvoice(cardRepository, invoiceRepository, cardEventPublisher, financeInvoice);
    }

    @Bean
    public FinanceInvoice financeInvoice(CardRepository cardRepository, InvoiceRepository invoiceRepository,
                                         CardEventPublisher cardEventPublisher) {
        return new FinanceInvoice(cardRepository, invoiceRepository, cardEventPublisher);
    }

    @Bean
    public AnticipateInstallments anticipateInstallments(CardRepository cardRepository, InvoiceRepository invoiceRepository) {
        return new AnticipateInstallments(cardRepository, invoiceRepository);
    }

    @Bean
    public DisputeRepository disputeRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcDisputeRepository(jdbcTemplate);
    }

    @Bean
    public DisputePurchase disputePurchase(CardRepository cardRepository, InvoiceRepository invoiceRepository,
                                           DisputeRepository disputeRepository, CardEventPublisher cardEventPublisher,
                                           VirtualCards virtualCards, Cashbacks cashbacks) {
        return new DisputePurchase(cardRepository, invoiceRepository, disputeRepository, cardEventPublisher, virtualCards,
                cashbacks);
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
    public Cashbacks cashbacks(CardRepository cardRepository, InvoiceRepository invoiceRepository,
                               DisputeRepository disputeRepository, CardEventPublisher cardEventPublisher) {
        return new Cashbacks(cardRepository, invoiceRepository, disputeRepository, cardEventPublisher);
    }

    @Bean
    public PayInvoice payInvoice(CardRepository cardRepository, InvoiceRepository invoiceRepository,
                                 CardBillingAccountService cardBillingAccountService,
                                 CardEventPublisher cardEventPublisher, Cashbacks cashbacks) {
        return new PayInvoice(cardRepository, invoiceRepository, cardBillingAccountService, cardEventPublisher, cashbacks);
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
    public CancelCard cancelCard(CardRepository cardRepository, VirtualCards virtualCards, CardPlastics cardPlastics) {
        return new CancelCard(cardRepository, virtualCards, cardPlastics);
    }
}
