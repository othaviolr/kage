package com.kage.customer.infrastructure.persistence;

import com.kage.customer.domain.entity.Customer;
import com.kage.customer.domain.repository.CustomerRepository;
import com.kage.customer.infrastructure.persistence.entity.CustomerJpaRepository;
import com.kage.customer.infrastructure.persistence.mapper.CustomerMapper;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
public class CustomerRepositoryImpl implements CustomerRepository {

    private final CustomerJpaRepository jpaRepository;

    public CustomerRepositoryImpl(CustomerJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Customer save(Customer customer) {
        var jpaEntity = CustomerMapper.toJpa(customer);
        var saved = jpaRepository.save(jpaEntity);
        return CustomerMapper.toDomain(saved);
    }

    @Override
    public Optional<Customer> findById(UUID id) {
        return jpaRepository.findById(id).map(CustomerMapper::toDomain);
    }

    @Override
    public Optional<Customer> findByCpf(String cpf) {
        return jpaRepository.findByCpf(cpf).map(CustomerMapper::toDomain);
    }

    @Override
    public Optional<Customer> findByEmail(String email) {
        return jpaRepository.findByEmail(email).map(CustomerMapper::toDomain);
    }

    @Override
    public boolean existsByCpf(String cpf) {
        return jpaRepository.existsByCpf(cpf);
    }

    @Override
    public boolean existsByEmail(String email) {
        return jpaRepository.existsByEmail(email);
    }

    @Override
    public void deleteById(UUID id) {
        jpaRepository.deleteById(id);
    }
}