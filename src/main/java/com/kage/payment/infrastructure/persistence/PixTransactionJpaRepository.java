package com.kage.payment.infrastructure.persistence;

import com.kage.payment.domain.enums.PixTransactionStatus;
import com.kage.payment.infrastructure.persistence.entity.PixTransactionJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface PixTransactionJpaRepository extends JpaRepository<PixTransactionJpaEntity, UUID> {
    Optional<PixTransactionJpaEntity> findByIdempotencyKey(UUID idempotencyKey);

    @Query("""
            SELECT SUM(t.amount) FROM PixTransactionJpaEntity t
            WHERE t.sourceAccountId = :sourceAccountId
              AND t.createdAt >= :since
              AND t.status NOT IN :ignoredStatuses
            """)
    BigDecimal sumAmountSentSince(@Param("sourceAccountId") UUID sourceAccountId,
                                  @Param("since") LocalDateTime since,
                                  @Param("ignoredStatuses") Collection<PixTransactionStatus> ignoredStatuses);
}
