package com.kage.notification.infrastructure.persistence;

import com.kage.notification.domain.enums.NotificationCategory;
import com.kage.notification.domain.repository.NotificationPreferenceRepository;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.EnumSet;
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
}
