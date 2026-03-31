package com.kage.customer.infrastructure.persistence.mapper;

import com.kage.customer.domain.entity.Customer;
import com.kage.customer.domain.valueobject.*;
import com.kage.customer.infrastructure.persistence.entity.CustomerJpaEntity;

public class CustomerMapper {

    private CustomerMapper() {}

    public static CustomerJpaEntity toJpa(Customer customer) {
        return new CustomerJpaEntity(
                customer.getId(),
                customer.getPersonalInfo().fullName(),
                customer.getPersonalInfo().cpf().value(),
                customer.getPersonalInfo().email().value(),
                customer.getPersonalInfo().phone().value(),
                customer.getPersonalInfo().birthDate(),
                customer.getAddress().street(),
                customer.getAddress().number(),
                customer.getAddress().complement(),
                customer.getAddress().city(),
                customer.getAddress().state(),
                customer.getAddress().zipCode(),
                customer.getKycStatus(),
                customer.getStatus(),
                customer.getCreatedAt(),
                customer.getUpdatedAt()
        );
    }

    public static Customer toDomain(CustomerJpaEntity entity) {
        var personalInfo = new PersonalInfo(
                entity.getFullName(),
                new Cpf(entity.getCpf()),
                new Email(entity.getEmail()),
                new Phone(entity.getPhone()),
                entity.getBirthDate()
        );

        var address = new Address(
                entity.getStreet(),
                entity.getNumber(),
                entity.getComplement(),
                entity.getCity(),
                entity.getState(),
                entity.getZipCode()
        );

        return Customer.reconstitute(
                entity.getId(),
                personalInfo,
                address,
                entity.getKycStatus(),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}