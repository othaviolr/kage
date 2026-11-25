package com.kage.customer.application.usecase.createcustomer;

import com.kage.customer.domain.entity.Customer;
import com.kage.customer.domain.repository.CustomerRepository;
import com.kage.customer.domain.valueobject.Address;
import com.kage.customer.domain.valueobject.Cpf;
import com.kage.customer.domain.valueobject.Email;
import com.kage.customer.domain.valueobject.PersonalInfo;
import com.kage.customer.domain.valueobject.Phone;
import com.kage.shared.domain.exception.ConflictException;

public class CreateCustomerUseCase {

    private final CustomerRepository customerRepository;

    public CreateCustomerUseCase(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    public CreateCustomerOutput execute(CreateCustomerInput input) {
        // a checagem de duplicidade usa o valor normalizado (só dígitos / minúsculo), que é o que fica no banco
        var cpf = new Cpf(input.cpf());
        var email = new Email(input.email());

        if (customerRepository.existsByCpf(cpf.value())) {
            throw new ConflictException("CPF já cadastrado");
        }

        if (customerRepository.existsByEmail(email.value())) {
            throw new ConflictException("Email já cadastrado");
        }

        var personalInfo = new PersonalInfo(
                input.fullName(),
                cpf,
                email,
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