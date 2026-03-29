package com.kage.customer.application.usecase.createcustomer;

import com.kage.customer.domain.entity.Customer;
import com.kage.customer.domain.repository.CustomerRepository;
import com.kage.customer.domain.valueobject.Address;
import com.kage.customer.domain.valueobject.Cpf;
import com.kage.customer.domain.valueobject.Email;
import com.kage.customer.domain.valueobject.PersonalInfo;
import com.kage.customer.domain.valueobject.Phone;
import com.kage.shared.domain.exception.DomainException;

public class CreateCustomerUseCase {

    private final CustomerRepository customerRepository;

    public CreateCustomerUseCase(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    public CreateCustomerOutput execute(CreateCustomerInput input) {
        if (customerRepository.existsByCpf(input.cpf())) {
            throw new DomainException("CPF já cadastrado");
        }

        if (customerRepository.existsByEmail(input.email())) {
            throw new DomainException("Email já cadastrado");
        }

        var personalInfo = new PersonalInfo(
                input.fullName(),
                new Cpf(input.cpf()),
                new Email(input.email()),
                new Phone(input.phone()),
                input.birthDate()
        );

        var address = new Address(
                input.street(),
                input.number(),
                input.complement(),
                input.city(),
                input.state(),
                input.zipCode()
        );

        var customer = Customer.create(personalInfo, address);

        var saved = customerRepository.save(customer);

        return CreateCustomerOutput.from(saved);
    }
}