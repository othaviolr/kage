package com.kage.customer.application.usecase.getcustomer;

import com.kage.customer.domain.entity.Customer;

import java.util.UUID;

public record GetCustomerOutput(
        UUID id,
        String fullName,
        String cpf,
        String email,
        String phone,
        String status,
        String kycStatus
) {

    public static GetCustomerOutput from(Customer customer) {
        return new GetCustomerOutput(
                customer.getId(),
                customer.getPersonalInfo().fullName(),
                customer.getPersonalInfo().cpf().toString(),
                customer.getPersonalInfo().email().toString(),
                customer.getPersonalInfo().phone().toString(),
                customer.getStatus().name(),
                customer.getKycStatus().name()
        );
    }
}