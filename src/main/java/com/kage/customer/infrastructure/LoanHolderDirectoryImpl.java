package com.kage.customer.infrastructure;

import com.kage.customer.domain.repository.CustomerRepository;
import com.kage.loan.domain.service.LoanHolderDirectory;

import java.util.Optional;
import java.util.UUID;

public class LoanHolderDirectoryImpl implements LoanHolderDirectory {

    private final CustomerRepository customerRepository;

    public LoanHolderDirectoryImpl(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    @Override
    public Optional<Holder> holderOf(UUID customerId) {
        return customerRepository.findById(customerId)
                .map(c -> new Holder(c.getPersonalInfo().fullName(), c.getPersonalInfo().cpf().value()));
    }
}
