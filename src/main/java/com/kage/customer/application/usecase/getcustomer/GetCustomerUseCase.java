package com.kage.customer.application.usecase.getcustomer;

import com.kage.customer.domain.repository.CustomerRepository;
import com.kage.shared.domain.exception.DomainException;

public class GetCustomerUseCase {

    private final CustomerRepository customerRepository;

    public GetCustomerUseCase(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    public GetCustomerOutput execute(GetCustomerInput input) {
        var customer = customerRepository.findById(input.id())
                .orElseThrow(() -> new DomainException("Cliente não encontrado"));

        return GetCustomerOutput.from(customer);
    }
}