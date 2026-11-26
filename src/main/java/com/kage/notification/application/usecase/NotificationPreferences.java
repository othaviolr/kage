package com.kage.notification.application.usecase;

import com.kage.notification.domain.enums.NotificationCategory;
import com.kage.notification.domain.repository.NotificationPreferenceRepository;
import com.kage.notification.domain.valueobject.QuietHours;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.ValidationException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Preferências de push por categoria. Desligar tira só o push — o aviso continua na caixa de
 * entrada. Categorias obrigatórias (segurança, fatura, contestação/2ª via) não desligam, e avisos
 * críticos (PIX que falhou, compra recusada...) saem por push mesmo com a categoria desligada.
 */
public class NotificationPreferences {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    /** @param mandatory não dá para desligar */
    public record Preference(String category, String description, boolean enabled, boolean mandatory) {}

    /** @param activeNow o horário silencioso está valendo agora */
    public record QuietHoursOutput(boolean enabled, String start, String end, boolean activeNow) {}

    private final NotificationPreferenceRepository repository;
    private final Clock clock;

    public NotificationPreferences(NotificationPreferenceRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public List<Preference> list(UUID customerId) {
        Set<NotificationCategory> disabled = repository.disabledFor(customerId);
        return Arrays.stream(NotificationCategory.values())
                .map(c -> new Preference(c.name(), c.description(), c.isMandatory() || !disabled.contains(c), c.isMandatory()))
                .toList();
    }

    public List<Preference> set(UUID customerId, String category, Boolean enabled) {
        if (enabled == null) throw new ValidationException("Informe se a categoria fica ligada (enabled)");
        NotificationCategory parsed = parse(category);
        if (parsed.isMandatory() && !enabled) {
            throw new BusinessRuleException("Os avisos de \"" + parsed.description() + "\" não podem ser desligados");
        }
        repository.set(customerId, parsed, enabled, LocalDateTime.now(clock));
        return list(customerId);
    }

    /** Fatura digital: o e-mail da fatura no fechamento (desligado, a fatura segue no app). */
    public record InvoiceEmailOutput(boolean enabled) {}

    public InvoiceEmailOutput invoiceEmail(UUID customerId) {
        return new InvoiceEmailOutput(repository.invoiceEmailEnabled(customerId));
    }

    public InvoiceEmailOutput setInvoiceEmail(UUID customerId, Boolean enabled) {
        if (enabled == null) throw new ValidationException("Informe se quer receber a fatura por e-mail (enabled)");
        repository.setInvoiceEmail(customerId, enabled, LocalDateTime.now(clock));
        return new InvoiceEmailOutput(enabled);
    }

    public QuietHoursOutput quietHours(UUID customerId) {
        return toOutput(repository.quietHours(customerId).orElse(QuietHours.DEFAULT));
    }

    /**
     * Liga/desliga e ajusta o horário silencioso ("HH:mm"). Início e fim omitidos mantêm os atuais (ou o
     * padrão, 22:00–07:00).
     */
    public QuietHoursOutput setQuietHours(UUID customerId, Boolean enabled, String start, String end) {
        if (enabled == null) throw new ValidationException("Informe se o horário silencioso fica ligado (enabled)");
        QuietHours current = repository.quietHours(customerId).orElse(QuietHours.DEFAULT);
        QuietHours updated = new QuietHours(enabled, start == null ? current.start() : time(start, "start"),
                end == null ? current.end() : time(end, "end"));
        repository.saveQuietHours(customerId, updated, LocalDateTime.now(clock));
        return toOutput(updated);
    }

    private QuietHoursOutput toOutput(QuietHours q) {
        return new QuietHoursOutput(q.enabled(), q.start().format(HH_MM), q.end().format(HH_MM),
                q.silences(LocalTime.now(clock)));
    }

    private static LocalTime time(String value, String field) {
        try {
            return LocalTime.parse(value.strip(), HH_MM);
        } catch (DateTimeParseException e) {
            throw new ValidationException("Horário inválido em " + field + ": use HH:mm (ex.: 22:00)");
        }
    }

    private static NotificationCategory parse(String category) {
        try {
            return NotificationCategory.valueOf(category.strip().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ValidationException("Categoria inválida: use " + Arrays.toString(NotificationCategory.values()));
        }
    }
}
