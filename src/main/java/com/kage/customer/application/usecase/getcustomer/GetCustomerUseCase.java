package com.kage.customer.application.usecase.getcustomer;

import com.kage.customer.domain.repository.CustomerRepository;
import com.kage.shared.domain.exception.DomainException;
import com.kage.shared.domain.exception.NotFoundException;

public class GetCustomerUseCase {

    private final CustomerRepository customerRepository;

    public GetCustomerUseCase(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    public GetCustomerOutput execute(GetCustomerInput input) {
        var customer = customerRepository.findById(input.id())
                .orElseThrow(() -> new NotFoundException("Cliente não encontrado"));

        return GetCustomerOutput.from(customer);
    }
}