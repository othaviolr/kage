package com.kage.debt.infrastructure;

import com.kage.card.application.usecase.AnticipateInstallments;
import com.kage.card.domain.repository.CardRepository;
import com.kage.card.domain.repository.InvoiceRepository;
import com.kage.customer.domain.repository.CustomerRepository;
import com.kage.debt.application.MyDebts;
import com.kage.loan.domain.repository.LoanRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class DebtConfig {

    @Bean
    public MyDebts myDebts(CardRepository cardRepository, InvoiceRepository invoiceRepository,
                           AnticipateInstallments anticipateInstallments, LoanRepository loanRepository,
                           CustomerRepository customerRepository) {
        return new MyDebts(
                List.of(new CardDebtSource(cardRepository, invoiceRepository, anticipateInstallments),
                        new LoanDebtSource(loanRepository)),
                customerId -> customerRepository.findById(customerId).isPresent());
    }
}
