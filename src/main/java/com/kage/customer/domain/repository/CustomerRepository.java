package com.kage.customer.domain.repository;

import com.kage.customer.domain.entity.Customer;

import java.util.Optional;
import java.util.UUID;

public interface CustomerRepository {

    Customer save(Customer customer);
    Optional<Customer> findById(UUID id);
    Optional<Customer> findByCpf(String cpf);
    Optional<Customer> findByEmail(String email);
    boolean existsByCpf(String cpf);
    boolean existsByEmail(String email);
    void deleteById(UUID id);
}