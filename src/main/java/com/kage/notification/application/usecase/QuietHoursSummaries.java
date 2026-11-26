package com.kage.notification.application.usecase;

import com.kage.notification.domain.entity.Notification;
import com.kage.notification.domain.enums.NotificationCategory;
import com.kage.notification.domain.enums.NotificationType;
import com.kage.notification.domain.enums.PushSilencedReason;
import com.kage.notification.domain.repository.NotificationPreferenceRepository;
import com.kage.notification.domain.repository.NotificationRepository;
import com.kage.notification.domain.service.NotificationChannel;
import com.kage.notification.domain.valueobject.QuietHours;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Resumo ao fim do horário silencioso: quando a janela do cliente acaba, um push só ("chegaram 5
 * avisos enquanto…") no lugar dos que ficaram em silêncio. Conta só o que foi silenciado pelo horário
 * — o que o cliente desligou por categoria continua sem push.
 *
 * Idempotente pelo id de origem (cliente + fim da janela): duas rodadas ou duas instâncias não
 * duplicam. Janela que acabou há mais de {@link #CATCH_UP} (ex.: aplicação fora do ar) não gera
 * resumo — ele chegaria tarde demais para fazer sentido.
 */
public class QuietHoursSummaries {

    private static final Logger logger = LoggerFactory.getLogger(QuietHoursSummaries.class);
    static final Duration CATCH_UP = Duration.ofHours(3);
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    public record Output(LocalDateTime at, int customersWithQuietHours, int summariesSent) {}

    private final NotificationPreferenceRepository preferences;
    private final NotificationRepository notificationRepository;
    private final NotificationChannel channel;
    private final Clock clock;

    public QuietHoursSummaries(NotificationPreferenceRepository preferences, NotificationRepository notificationRepository,
                               NotificationChannel channel, Clock clock) {
        this.preferences = preferences;
        this.notificationRepository = notificationRepository;
        this.channel = channel;
        this.clock = clock;
    }

    /** @param at o "agora" da rodada (o job passa o relógio; o backoffice pode simular) */
    public Output run(LocalDateTime at) {
        Map<UUID, QuietHours> customers = preferences.enabledQuietHours();
        int sent = 0;
        for (Map.Entry<UUID, QuietHours> entry : customers.entrySet()) {
            try {
                if (summarize(entry.getKey(), entry.getValue(), at)) sent++;
            } catch (RuntimeException e) {
                logger.warn("Resumo do horário silencioso do cliente {} fica para a próxima rodada: {}", entry.getKey(), e.getMessage());
            }
        }
        if (sent > 0) logger.info("Resumo do horário silencioso enviado a {} cliente(s)", sent);
        return new Output(at, customers.size(), sent);
    }

    private boolean summarize(UUID customerId, QuietHours quiet, LocalDateTime at) {
        LocalDateTime end = lastEnd(quiet, at);
        if (Duration.between(end, at).compareTo(CATCH_UP) > 0) return false;
        LocalDateTime start = startOf(quiet, end);

        Map<NotificationType, Integer> silenced = notificationRepository.countSilenced(customerId,
                PushSilencedReason.QUIET_HOURS, start, end);
        int total = silenced.values().stream().mapToInt(Integer::intValue).sum();
        if (total == 0) return false;

        Map<NotificationCategory, Integer> byCategory = new EnumMap<>(NotificationCategory.class);
        silenced.forEach((type, count) -> byCategory.merge(type.category(), count, Integer::sum));
        String parts = byCategory.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .map(e -> e.getValue() + " de " + e.getKey().label())
                .collect(Collectors.joining(", "));

        String message = "Enquanto o horário silencioso estava ligado (" + quiet.start().format(HH_MM) + " às "
                + quiet.end().format(HH_MM) + "), " + (total == 1 ? "chegou 1 aviso" : "chegaram " + total + " avisos")
                + ": " + parts
                + ". " + (total == 1 ? "Ele está" : "Eles estão") + " na sua caixa de entrada.";
        Notification summary = Notification.create(customerId, NotificationType.QUIET_HOURS_SUMMARY,
                "Resumo do horário silencioso", message, null, sourceEventId(customerId, end), LocalDateTime.now(clock));
        if (!notificationRepository.saveIfNew(summary)) return false; // outra rodada já mandou

        try {
            channel.deliver(summary);
        } catch (RuntimeException e) {
            logger.warn("Falha ao entregar o resumo {}; segue na caixa de entrada", summary.getId(), e);
        }
        return true;
    }

    /** Id estável do resumo de uma janela (cabe nos 64 caracteres do source_event_id). */
    static String sourceEventId(UUID customerId, LocalDateTime end) {
        return "quiet-summary:" + UUID.nameUUIDFromBytes((customerId + ":" + end).getBytes(StandardCharsets.UTF_8));
    }

    /** O fim de janela mais recente que já passou (hoje ou ontem). */
    static LocalDateTime lastEnd(QuietHours quiet, LocalDateTime at) {
        LocalDateTime end = at.toLocalDate().atTime(quiet.end());
        return end.isAfter(at) ? end.minusDays(1) : end;
    }

    /** Início da janela que termina em {@code end} (no dia anterior, se ela atravessa a meia-noite). */
    static LocalDateTime startOf(QuietHours quiet, LocalDateTime end) {
        LocalDateTime start = end.toLocalDate().atTime(quiet.start());
        return start.isBefore(end) ? start : start.minusDays(1);
    }
}
