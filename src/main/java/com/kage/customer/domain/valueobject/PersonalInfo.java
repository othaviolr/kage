package com.kage.customer.domain.valueobject;

import com.kage.shared.domain.exception.ValidationException;

import java.time.LocalDate;

public record PersonalInfo(String fullName, Cpf cpf, Email email, Phone phone, LocalDate birthDate)
{
    public PersonalInfo {
        if (fullName == null || fullName.isBlank()) throw new ValidationException("Nome é obrigatório");
        if (cpf == null) throw new ValidationException("CPF é obrigatório");
        if (email == null) throw new ValidationException("Email é obrigatório");
        if (phone == null) throw new ValidationException("Telefone é obrigatório");
        if (birthDate == null || birthDate.isAfter(LocalDate.now().minusYears(18))) {
            throw new ValidationException("Cliente deve ter pelo menos 18 anos");
        }
    }
}