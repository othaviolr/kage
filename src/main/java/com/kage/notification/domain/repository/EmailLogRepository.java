package com.kage.notification.domain.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Registro dos e-mails enviados ao cliente: auditoria da comunicação (o banco precisa provar que
 * mandou a fatura) e idempotência — o mesmo evento não gera dois e-mails. O anexo não é guardado (o
 * PDF sai de novo da fatura); fica o nome, o tamanho e o SHA-256.
 */
public interface EmailLogRepository {

    /** SKIPPED: não mandado por opção do cliente (fica registrado que a fatura fechou e por que não foi). */
    enum Status { PENDING, SENT, FAILED, SKIPPED }

    record EmailLog(UUID id, UUID customerId, String kind, String toAddress, String subject, String attachmentName,
                    Integer attachmentSize, String attachmentSha256, UUID referenceId, String sourceEventId,
                    Status status, String failureReason, LocalDateTime createdAt, LocalDateTime sentAt) {}

    /** Cria o registro (PENDING, ou SKIPPED). @return false se já existe um para esse evento */
    boolean insertIfNew(EmailLog log);

    Optional<EmailLog> findBySourceEventId(String sourceEventId);

    void markSent(UUID id, LocalDateTime sentAt);

    void markFailed(UUID id, String reason);

    /** Do cliente, do mais novo para o mais antigo. */
    List<EmailLog> findByCustomer(UUID customerId, int limit);
}
