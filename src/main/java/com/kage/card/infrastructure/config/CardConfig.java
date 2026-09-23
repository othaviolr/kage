package com.kage.card.infrastructure.config;

import com.kage.card.application.usecase.*;
import com.kage.card.domain.repository.CardRepository;
import com.kage.card.domain.repository.InvoiceRepository;
import com.kage.card.infrastructure.persistence.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
    public IssueCard issueCard(CardRepository cardRepository) {
        return new IssueCard(cardRepository);
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
}
