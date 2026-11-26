package com.kage.notification.infrastructure.persistence;

import com.kage.notification.domain.entity.Notification;
import com.kage.notification.domain.enums.NotificationType;
import com.kage.notification.domain.enums.PushSilencedReason;
import com.kage.notification.domain.repository.NotificationRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class NotificationRepositoryImpl implements NotificationRepository {

    private static final String COLUMNS =
            "id, customer_id, type, title, message, reference_id, source_event_id, created_at, read_at, pushed, "
                    + "push_silenced_reason";

    private static final RowMapper<Notification> MAPPER = (rs, rowNum) -> Notification.reconstitute(
            rs.getObject("id", UUID.class),
            rs.getObject("customer_id", UUID.class),
            NotificationType.valueOf(rs.getString("type")),
            rs.getString("title"),
            rs.getString("message"),
            rs.getObject("reference_id", UUID.class),
            rs.getString("source_event_id"),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("read_at") == null ? null : rs.getTimestamp("read_at").toLocalDateTime(),
            rs.getBoolean("pushed"),
            rs.getString("push_silenced_reason") == null ? null : PushSilencedReason.valueOf(rs.getString("push_silenced_reason")));

    private final JdbcTemplate jdbcTemplate;

    public NotificationRepositoryImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean saveIfNew(Notification n) {
        // ON CONFLICT em vez de capturar exceção: num INSERT que falha o Postgres aborta a transação inteira
        int inserted = jdbcTemplate.update("INSERT INTO notifications (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (source_event_id) DO NOTHING",
                n.getId(), n.getCustomerId(), n.getType().name(), n.getTitle(), n.getMessage(), n.getReferenceId(),
                n.getSourceEventId(), Timestamp.valueOf(n.getCreatedAt()),
                n.getReadAt() == null ? null : Timestamp.valueOf(n.getReadAt()), n.isPushed(),
                n.getSilencedReason() == null ? null : n.getSilencedReason().name());
        return inserted == 1;
    }

    @Override
    public void markAsRead(Notification n) {
        jdbcTemplate.update("UPDATE notifications SET read_at = ? WHERE id = ? AND read_at IS NULL",
                Timestamp.valueOf(n.getReadAt()), n.getId());
    }

    @Override
    public Optional<Notification> findById(UUID id) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM notifications WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    @Override
    public List<Notification> findByCustomer(UUID customerId, boolean unreadOnly, int limit) {
        String sql = "SELECT " + COLUMNS + " FROM notifications WHERE customer_id = ?"
                + (unreadOnly ? " AND read_at IS NULL" : "")
                + " ORDER BY created_at DESC LIMIT ?";
        return jdbcTemplate.query(sql, MAPPER, customerId, limit);
    }

    @Override
    public Map<NotificationType, Integer> countSilenced(UUID customerId, PushSilencedReason reason, LocalDateTime from,
                                                        LocalDateTime to) {
        Map<NotificationType, Integer> counts = new EnumMap<>(NotificationType.class);
        jdbcTemplate.query("SELECT type, COUNT(*) AS total FROM notifications WHERE customer_id = ? "
                        + "AND push_silenced_reason = ? AND created_at >= ? AND created_at < ? GROUP BY type",
                rs -> {
                    counts.put(NotificationType.valueOf(rs.getString("type")), rs.getInt("total"));
                }, customerId, reason.name(), Timestamp.valueOf(from), Timestamp.valueOf(to));
        return counts;
    }

    @Override
    public int countUnread(UUID customerId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE customer_id = ? AND read_at IS NULL", Integer.class, customerId);
        return count == null ? 0 : count;
    }
}
