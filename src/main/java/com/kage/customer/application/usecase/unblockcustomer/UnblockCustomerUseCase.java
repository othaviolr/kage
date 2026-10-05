package com.kage.customer.application.usecase.unblockcustomer;

import com.kage.customer.domain.repository.CustomerRepository;
import com.kage.shared.domain.exception.NotFoundException;

public class UnblockCustomerUseCase {

    private final CustomerRepository customerRepository;

    public UnblockCustomerUseCase(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    public UnblockCustomerOutput execute(UnblockCustomerInput input) {
        var customer = customerRepository.findById(input.id())
                .orElseThrow(() -> new NotFoundException("Cliente não encontrado"));

        customer.unblock();

        var saved = customerRepository.save(customer);

        return UnblockCustomerOutput.from(saved);
    }
}
