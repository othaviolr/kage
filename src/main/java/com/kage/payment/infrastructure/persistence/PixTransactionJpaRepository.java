package com.kage.payment.infrastructure.persistence;

import com.kage.payment.domain.enums.PixTransactionStatus;
import com.kage.payment.infrastructure.persistence.entity.PixTransactionJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PixTransactionJpaRepository extends JpaRepository<PixTransactionJpaEntity, UUID> {
    Optional<PixTransactionJpaEntity> findByIdempotencyKey(UUID idempotencyKey);

    /**
     * Soma pelo momento em que o dinheiro saiu (processedAt), não pelo da criação: um PIX agendado
     * hoje para o mês que vem consome o limite do dia em que for executado, não o de hoje.
     */
    @Query("""
            SELECT SUM(t.amount) FROM PixTransactionJpaEntity t
            WHERE t.sourceAccountId = :sourceAccountId
              AND t.processedAt >= :since
              AND t.status IN :countedStatuses
            """)
    BigDecimal sumAmountSentSince(@Param("sourceAccountId") UUID sourceAccountId,
                                  @Param("since") LocalDateTime since,
                                  @Param("countedStatuses") Collection<PixTransactionStatus> countedStatuses);

    @Query("""
            SELECT t FROM PixTransactionJpaEntity t
            WHERE t.transactionType = com.kage.payment.domain.enums.TransactionType.SCHEDULED
              AND t.status = com.kage.payment.domain.enums.PixTransactionStatus.PENDING
              AND t.scheduledDate < :until
            ORDER BY t.scheduledDate, t.createdAt
            """)
    List<PixTransactionJpaEntity> findScheduledDueBefore(@Param("until") LocalDateTime until);

    @Query("""
            SELECT t FROM PixTransactionJpaEntity t
            WHERE t.transactionType = com.kage.payment.domain.enums.TransactionType.SCHEDULED
              AND t.status = com.kage.payment.domain.enums.PixTransactionStatus.PENDING
              AND t.sourceAccountId = :accountId
            ORDER BY t.scheduledDate
            """)
    List<PixTransactionJpaEntity> findPendingScheduledByAccount(@Param("accountId") UUID accountId);
}
