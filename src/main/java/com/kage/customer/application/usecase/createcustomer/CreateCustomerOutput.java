package com.kage.customer.application.usecase.createcustomer;

import com.kage.customer.domain.entity.Customer;

import java.util.UUID;

public record CreateCustomerOutput(
        UUID id,
        String fullName,
        String cpf,
        String email,
        String status,
        String kycStatus
) {

    public static CreateCustomerOutput from(Customer customer) {
        return new CreateCustomerOutput(
                customer.getId(),
                customer.getPersonalInfo().fullName(),
                customer.getPersonalInfo().cpf().toString(),
                customer.getPersonalInfo().email().toString(),
                customer.getStatus().name(),
                customer.getKycStatus().name()
        );
    }
}