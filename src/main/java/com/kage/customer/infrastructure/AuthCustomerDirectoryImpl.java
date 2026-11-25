package com.kage.customer.infrastructure;

import com.kage.auth.domain.service.CustomerDirectory;
import com.kage.customer.application.usecase.createcustomer.CreateCustomerInput;
import com.kage.customer.application.usecase.createcustomer.CreateCustomerUseCase;
import com.kage.customer.domain.enums.CustomerStatus;
import com.kage.customer.domain.repository.CustomerRepository;

import java.util.Optional;
import java.util.UUID;

/** Lado Customer da porta do auth: cadastro pelo mesmo use case do backoffice e busca por CPF. */
public class AuthCustomerDirectoryImpl implements CustomerDirectory {

    private final CreateCustomerUseCase createCustomer;
    private final CustomerRepository customerRepository;

    public AuthCustomerDirectoryImpl(CreateCustomerUseCase createCustomer, CustomerRepository customerRepository) {
        this.createCustomer = createCustomer;
        this.customerRepository = customerRepository;
    }

    @Override
    public RegisteredCustomer register(NewCustomer c) {
        var output = createCustomer.execute(new CreateCustomerInput(c.fullName(), c.cpf(), c.email(), c.phone(),
                c.birthDate(), c.street(), c.number(), c.complement(), c.city(), c.state(), c.zipCode()));
        return new RegisteredCustomer(output.id(), output.cpf().replaceAll("\\D", ""), output.status(), output.kycStatus());
    }

    @Override
    public Optional<Boolean> isBlocked(UUID customerId) {
        return customerRepository.findById(customerId).map(customer -> customer.getStatus() == CustomerStatus.BLOCKED);
    }

    @Override
    public Optional<String> cpfDigitsOf(UUID customerId) {
        return customerRepository.findById(customerId).map(customer -> customer.getPersonalInfo().cpf().value());
    }

    @Override
    public Optional<CustomerIdentity> findByCpf(String cpf) {
        // CPF malformado não é erro de validação aqui: para o login é só "não encontrado" (401 genérico)
        String digits = cpf == null ? "" : cpf.replaceAll("\\D", "");
        if (digits.length() != 11) return Optional.empty();

        return customerRepository.findByCpf(digits)
                .map(customer -> new CustomerIdentity(customer.getId(), customer.getStatus() == CustomerStatus.BLOCKED));
    }
}
