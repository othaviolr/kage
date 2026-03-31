package com.kage.customer.infrastructure.persistence.entity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CustomerJpaRepository extends JpaRepository<CustomerJpaEntity, UUID> {

    Optional<CustomerJpaEntity> findByCpf(String cpf);
    Optional<CustomerJpaEntity> findByEmail(String email);
    boolean existsByCpf(String cpf);
    boolean existsByEmail(String email);
}