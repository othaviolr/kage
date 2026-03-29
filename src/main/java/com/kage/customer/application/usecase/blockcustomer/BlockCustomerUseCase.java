package com.kage.customer.application.usecase.blockcustomer;

import com.kage.customer.domain.repository.CustomerRepository;
import com.kage.shared.domain.exception.DomainException;

public class BlockCustomerUseCase {

    private final CustomerRepository customerRepository;

    public BlockCustomerUseCase(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    public BlockCustomerOutput execute(BlockCustomerInput input) {
        var customer = customerRepository.findById(input.id())
                .orElseThrow(() -> new DomainException("Cliente não encontrado"));

        customer.block();

        var saved = customerRepository.save(customer);

        return BlockCustomerOutput.from(saved);
    }
}