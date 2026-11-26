package com.kage.customer.infrastructure;

import com.kage.card.domain.service.CardHolderDirectory;
import com.kage.customer.domain.repository.CustomerRepository;

import java.util.Optional;
import java.util.UUID;

public class CardHolderDirectoryImpl implements CardHolderDirectory {

    private final CustomerRepository customerRepository;

    public CardHolderDirectoryImpl(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    @Override
    public Optional<String> nameOf(UUID customerId) {
        return customerRepository.findById(customerId).map(customer -> customer.getPersonalInfo().fullName());
    }
}
