package com.kage.auth.domain.repository;

import com.kage.auth.domain.entity.Credential;

import java.util.Optional;
import java.util.UUID;

public interface CredentialRepository {

    void insert(Credential credential);

    void update(Credential credential);

    Optional<Credential> findByCustomerId(UUID customerId);
}
