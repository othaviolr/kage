package com.kage.payment.infrastructure.persistence.mapper;

import com.kage.payment.domain.entity.PixKey;
import com.kage.payment.infrastructure.persistence.entity.PixKeyJpaEntity;

public class PixKeyMapper {

    public static PixKeyJpaEntity toJpa(PixKey pixKey) {
        return new PixKeyJpaEntity(
                pixKey.getPixKeyId(),
                pixKey.getAccountId(),
                pixKey.getKeyType(),
                pixKey.getKeyValue(),
                pixKey.getKeyStatus(),
                pixKey.getCreatedAt(),
                pixKey.getDeletedAt()
        );
    }

    public static PixKey toDomain(PixKeyJpaEntity entity) {
        return PixKey.reconstitute(
                entity.getPixKeyId(),
                entity.getAccountId(),
                entity.getKeyType(),
                entity.getKeyValue(),
                entity.getKeyStatus(),
                entity.getCreatedAt(),
                entity.getDeletedAt()
        );
    }
}