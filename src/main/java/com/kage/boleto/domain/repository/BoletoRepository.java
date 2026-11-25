package com.kage.boleto.domain.repository;

import com.kage.boleto.domain.entity.Boleto;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BoletoRepository {

    long nextOurNumber();

    void insert(Boleto boleto);

    /** Locking otimista: dois pagamentos simultâneos do mesmo boleto -> o segundo leva 409. */
    void update(Boleto boleto);

    Optional<Boleto> findById(UUID id);

    Optional<Boleto> findByBarcode(String barcode);

    List<Boleto> findByBeneficiaryAccount(UUID accountId, int limit);
}
