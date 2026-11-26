package com.kage.loan.domain.service;

import java.util.Optional;
import java.util.UUID;

/** Porta para o titular do empréstimo (nome e CPF), que vive no contexto de clientes. */
public interface LoanHolderDirectory {

    record Holder(String fullName, String cpf) {}

    Optional<Holder> holderOf(UUID customerId);
}
