package com.kage.notification.application.usecase;

import com.kage.notification.domain.enums.NotificationCategory;
import com.kage.notification.domain.repository.NotificationPreferenceRepository;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.ValidationException;

import java.time.Clock;
import java.time.LocalDateTime;
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

    /** @param mandatory não dá para desligar */
    public record Preference(String category, String description, boolean enabled, boolean mandatory) {}

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

    private static NotificationCategory parse(String category) {
        try {
            return NotificationCategory.valueOf(category.strip().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ValidationException("Categoria inválida: use " + Arrays.toString(NotificationCategory.values()));
        }
    }
}
