package com.kage.customer.application.usecase.updateaddress;

import com.kage.customer.domain.repository.CustomerRepository;
import com.kage.customer.domain.valueobject.Address;
import com.kage.shared.domain.exception.DomainException;

public class UpdateAddressUseCase {

    private final CustomerRepository customerRepository;

    public UpdateAddressUseCase(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    public UpdateAddressOutput execute(UpdateAddressInput input) {
        var customer = customerRepository.findById(input.id())
                .orElseThrow(() -> new DomainException("Cliente não encontrado"));

        var newAddress = new Address(
                input.street(),
                input.number(),
                input.complement(),
                input.city(),
                input.state(),
                input.zipCode()
        );

        customer.updateAddress(newAddress);

        var saved = customerRepository.save(customer);

        return UpdateAddressOutput.from(saved);
    }
}