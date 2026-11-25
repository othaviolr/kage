package com.kage.customer.application.usecase.blockcustomer;

import com.kage.customer.application.usecase.CustomerEventPublisher;
import com.kage.customer.domain.repository.CustomerRepository;
import com.kage.shared.domain.exception.NotFoundException;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bloqueia o cliente e publica customer.blocked na mesma transação: quem reage (o auth encerra
 * todas as sessões dele) nunca perde o bloqueio, nem age sobre um bloqueio que sofreu rollback.
 */
public class BlockCustomerUseCase {

    private final CustomerRepository customerRepository;
    private final CustomerEventPublisher eventPublisher;

    public BlockCustomerUseCase(CustomerRepository customerRepository, CustomerEventPublisher eventPublisher) {
        this.customerRepository = customerRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public BlockCustomerOutput execute(BlockCustomerInput input) {
        var customer = customerRepository.findById(input.id())
                .orElseThrow(() -> new NotFoundException("Cliente não encontrado"));

        customer.block();

        var saved = customerRepository.save(customer);
        eventPublisher.publishCustomerBlocked(saved.getId());

        return BlockCustomerOutput.from(saved);
    }
}
