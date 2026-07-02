package com.kage.payment.application.usecase;

import com.kage.payment.domain.entity.PixKey;
import com.kage.payment.domain.repository.PixKeyRepository;
import com.kage.shared.domain.exception.DomainException;
import com.kage.shared.domain.exception.NotFoundException;

import java.util.UUID;

public class DeletePixKey {

    private final PixKeyRepository pixKeyRepository;

    public DeletePixKey(PixKeyRepository pixKeyRepository) {
        this.pixKeyRepository = pixKeyRepository;
    }

    public void execute(UUID pixKeyId) {
        PixKey pixKey = pixKeyRepository.findById(pixKeyId).orElseThrow(() -> new NotFoundException("Chave PIX não encontrada"));

        pixKey.delete();
        pixKeyRepository.save(pixKey);
    }
}