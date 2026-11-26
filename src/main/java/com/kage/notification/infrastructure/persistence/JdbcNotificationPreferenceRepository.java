package com.kage.notification.infrastructure.persistence;

import com.kage.notification.domain.enums.NotificationCategory;
import com.kage.notification.domain.repository.NotificationPreferenceRepository;
import org.springframework.jdbc.core.JdbcTemplate;

import com.kage.notification.domain.valueobject.QuietHours;

import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class JdbcNotificationPreferenceRepository implements NotificationPreferenceRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcNotificationPreferenceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Set<NotificationCategory> disabledFor(UUID customerId) {
        Set<NotificationCategory> disabled = EnumSet.noneOf(NotificationCategory.class);
        jdbcTemplate.queryForList("SELECT category FROM notification_preferences WHERE customer_id = ? AND NOT enabled",
                String.class, customerId).forEach(c -> disabled.add(NotificationCategory.valueOf(c)));
        return disabled;
    }

    @Override
    public void set(UUID customerId, NotificationCategory category, boolean enabled, LocalDateTime now) {
        jdbcTemplate.update("INSERT INTO notification_preferences (customer_id, category, enabled, updated_at) "
                        + "VALUES (?, ?, ?, ?) ON CONFLICT (customer_id, category) "
                        + "DO UPDATE SET enabled = EXCLUDED.enabled, updated_at = EXCLUDED.updated_at",
                customerId, category.name(), enabled, Timestamp.valueOf(now));
    }

    @Override
    public Optional<QuietHours> quietHours(UUID customerId) {
        return jdbcTemplate.query("SELECT enabled, start_time, end_time FROM notification_quiet_hours WHERE customer_id = ?",
                (rs, n) -> new QuietHours(rs.getBoolean("enabled"), rs.getTime("start_time").toLocalTime(),
                        rs.getTime("end_time").toLocalTime()), customerId).stream().findFirst();
    }

    @Override
    public boolean invoiceEmailEnabled(UUID customerId) {
        return jdbcTemplate.queryForList("SELECT enabled FROM invoice_email_settings WHERE customer_id = ?", Boolean.class,
                customerId).stream().findFirst().orElse(true);
    }

    @Override
    public void setInvoiceEmail(UUID customerId, boolean enabled, LocalDateTime now) {
        jdbcTemplate.update("INSERT INTO invoice_email_settings (customer_id, enabled, updated_at) VALUES (?, ?, ?) "
                        + "ON CONFLICT (customer_id) DO UPDATE SET enabled = EXCLUDED.enabled, updated_at = EXCLUDED.updated_at",
                customerId, enabled, Timestamp.valueOf(now));
    }

    @Override
    public void saveQuietHours(UUID customerId, QuietHours q, LocalDateTime now) {
        jdbcTemplate.update("INSERT INTO notification_quiet_hours (customer_id, enabled, start_time, end_time, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?) ON CONFLICT (customer_id) DO UPDATE SET enabled = EXCLUDED.enabled, "
                        + "start_time = EXCLUDED.start_time, end_time = EXCLUDED.end_time, updated_at = EXCLUDED.updated_at",
                customerId, q.enabled(), Time.valueOf(q.start()), Time.valueOf(q.end()), Timestamp.valueOf(now));
    }
}
