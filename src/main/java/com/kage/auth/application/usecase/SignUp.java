package com.kage.auth.application.usecase;

import com.kage.auth.domain.entity.Credential;
import com.kage.auth.domain.repository.CredentialRepository;
import com.kage.auth.domain.service.CustomerDirectory;
import com.kage.auth.domain.service.PasswordHasher;
import com.kage.auth.domain.valueobject.PasswordPolicy;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Abertura de conta pelo app: cadastra o cliente e cria a credencial dele na mesma transação — não
 * existe cliente que se cadastrou e ficou sem senha, nem senha órfã.
 */
public class SignUp {

    public record Input(String fullName, String cpf, String email, String phone, LocalDate birthDate,
                        String street, String number, String complement, String city, String state, String zipCode,
                        String password) {}

    public record Output(UUID customerId, String status, String kycStatus) {}

    private final CustomerDirectory customerDirectory;
    private final CredentialRepository credentialRepository;
    private final PasswordHasher passwordHasher;
    private final Clock clock;

    public SignUp(CustomerDirectory customerDirectory, CredentialRepository credentialRepository,
                  PasswordHasher passwordHasher, Clock clock) {
        this.customerDirectory = customerDirectory;
        this.credentialRepository = credentialRepository;
        this.passwordHasher = passwordHasher;
        this.clock = clock;
    }

    @Transactional
    public Output execute(Input input) {
        // valida o formato da senha antes de cadastrar; a regra "não contém o CPF" precisa do CPF normalizado
        PasswordPolicy.validate(input.password(), null);

        var registered = customerDirectory.register(new CustomerDirectory.NewCustomer(input.fullName(), input.cpf(),
                input.email(), input.phone(), input.birthDate(), input.street(), input.number(), input.complement(),
                input.city(), input.state(), input.zipCode()));

        PasswordPolicy.validate(input.password(), registered.cpfDigits()); // falhar aqui desfaz o cadastro

        credentialRepository.insert(Credential.create(registered.customerId(),
                passwordHasher.hash(input.password()), LocalDateTime.now(clock)));

        return new Output(registered.customerId(), registered.status(), registered.kycStatus());
    }
}
