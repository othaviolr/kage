package com.kage.customer.application.usecase.createcustomer;

import java.time.LocalDate;

public record CreateCustomerInput(
        String fullName,
        String cpf,
        String email,
        String phone,
        LocalDate birthDate,
        String street,
        String number,
        String complement,
        String city,
        String state,
        String zipCode
) {}