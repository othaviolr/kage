package com.kage.notification.infrastructure.persistence;

import com.kage.notification.domain.repository.EmailLogRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class JdbcEmailLogRepository implements EmailLogRepository {

    private static final String COLUMNS = "id, customer_id, kind, to_address, subject, attachment_name, attachment_size, "
            + "attachment_sha256, reference_id, source_event_id, status, failure_reason, created_at, sent_at";

    private static final RowMapper<EmailLog> MAPPER = (rs, n) -> new EmailLog(
            rs.getObject("id", UUID.class), rs.getObject("customer_id", UUID.class), rs.getString("kind"),
            rs.getString("to_address"), rs.getString("subject"), rs.getString("attachment_name"),
            (Integer) rs.getObject("attachment_size"), rs.getString("attachment_sha256"),
            rs.getObject("reference_id", UUID.class), rs.getString("source_event_id"), Status.valueOf(rs.getString("status")),
            rs.getString("failure_reason"), rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("sent_at") == null ? null : rs.getTimestamp("sent_at").toLocalDateTime());

    private final JdbcTemplate jdbcTemplate;

    public JdbcEmailLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean insertIfNew(EmailLog l) {
        return jdbcTemplate.update("INSERT INTO email_log (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (source_event_id) DO NOTHING",
                l.id(), l.customerId(), l.kind(), l.toAddress(), l.subject(), l.attachmentName(), l.attachmentSize(),
                l.attachmentSha256(), l.referenceId(), l.sourceEventId(), l.status().name(), l.failureReason(),
                Timestamp.valueOf(l.createdAt()), l.sentAt() == null ? null : Timestamp.valueOf(l.sentAt())) == 1;
    }

    @Override
    public Optional<EmailLog> findBySourceEventId(String sourceEventId) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM email_log WHERE source_event_id = ?", MAPPER, sourceEventId)
                .stream().findFirst();
    }

    @Override
    public void markSent(UUID id, LocalDateTime sentAt) {
        jdbcTemplate.update("UPDATE email_log SET status = 'SENT', sent_at = ?, failure_reason = NULL WHERE id = ?",
                Timestamp.valueOf(sentAt), id);
    }

    @Override
    public void markFailed(UUID id, String reason) {
        String trimmed = reason == null ? null : reason.substring(0, Math.min(reason.length(), 255));
        jdbcTemplate.update("UPDATE email_log SET status = 'FAILED', failure_reason = ? WHERE id = ? AND status <> 'SENT'",
                trimmed, id);
    }

    @Override
    public List<EmailLog> findByCustomer(UUID customerId, int limit) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM email_log WHERE customer_id = ? ORDER BY created_at DESC LIMIT ?",
                MAPPER, customerId, limit);
    }
}
