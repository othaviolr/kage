package com.kage.customer.application.usecase.unblockcustomer;

import com.kage.customer.domain.entity.Customer;

import java.util.UUID;

public record UnblockCustomerOutput(
        UUID id,
        String status
) {

    public static UnblockCustomerOutput from(Customer customer) {
        return new UnblockCustomerOutput(
                customer.getId(),
                customer.getStatus().name()
        );
    }
}
