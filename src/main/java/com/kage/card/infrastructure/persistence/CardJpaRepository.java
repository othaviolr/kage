package com.kage.card.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CardJpaRepository extends JpaRepository<CardJpaEntity, UUID> {

    @org.springframework.data.jpa.repository.Query("SELECT c.id FROM CardJpaEntity c WHERE c.status = com.kage.card.domain.enums.CardStatus.ACTIVE")
    java.util.List<UUID> findActiveIds();
}
