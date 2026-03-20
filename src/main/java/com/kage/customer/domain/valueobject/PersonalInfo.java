package com.kage.customer.domain.valueobject;

import com.kage.shared.domain.exception.DomainException;

import java.time.LocalDate;

public record PersonalInfo(String fullName, Cpf cpf, Email email, Phone phone, LocalDate birthDate)
{
    public PersonalInfo {
        if (fullName == null || fullName.isBlank()) throw new DomainException("Nome é obrigatório");
        if (cpf == null) throw new DomainException("CPF é obrigatório");
        if (email == null) throw new DomainException("Email é obrigatório");
        if (phone == null) throw new DomainException("Telefone é obrigatório");
        if (birthDate == null || birthDate.isAfter(LocalDate.now().minusYears(18))) {
            throw new DomainException("Cliente deve ter pelo menos 18 anos");
        }
    }
}