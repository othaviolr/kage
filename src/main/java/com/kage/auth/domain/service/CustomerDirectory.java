package com.kage.auth.domain.service;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta do auth para o contexto Customer: cadastrar quem está criando acesso e descobrir quem está
 * tentando entrar. A implementação vive em customer.infrastructure.
 */
public interface CustomerDirectory {

    RegisteredCustomer register(NewCustomer customer);

    Optional<CustomerIdentity> findByCpf(String cpf);

    /** @return vazio se o cliente não existe */
    Optional<Boolean> isBlocked(java.util.UUID customerId);

    /** CPF só com dígitos, para a política de senha ("não pode conter o CPF"). */
    Optional<String> cpfDigitsOf(java.util.UUID customerId);

    record NewCustomer(String fullName, String cpf, String email, String phone, LocalDate birthDate,
                       String street, String number, String complement, String city, String state, String zipCode) {}

    /** @param cpfDigits CPF já normalizado (só dígitos) */
    record RegisteredCustomer(UUID customerId, String cpfDigits, String status, String kycStatus) {}

    record CustomerIdentity(UUID customerId, boolean blocked) {}
}
