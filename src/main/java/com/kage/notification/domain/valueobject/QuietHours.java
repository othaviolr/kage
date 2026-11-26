package com.kage.notification.domain.valueobject;

import com.kage.shared.domain.exception.ValidationException;

import java.time.LocalTime;
import java.time.temporal.ChronoUnit;

/**
 * Horário silencioso: entre {@code start} (inclusive) e {@code end} (exclusive) os avisos não críticos
 * não vão por push — ficam só na caixa de entrada. A janela pode atravessar a meia-noite (22h–7h).
 */
public record QuietHours(boolean enabled, LocalTime start, LocalTime end) {

    public static final QuietHours DEFAULT = new QuietHours(false, LocalTime.of(22, 0), LocalTime.of(7, 0));

    public QuietHours {
        if (start == null || end == null) throw new ValidationException("Informe o início e o fim do horário silencioso");
        start = start.truncatedTo(ChronoUnit.MINUTES);
        end = end.truncatedTo(ChronoUnit.MINUTES);
        if (start.equals(end)) throw new ValidationException("Início e fim do horário silencioso não podem ser iguais");
    }

    /** Agora está dentro da janela (e ela está ligada). */
    public boolean silences(LocalTime now) {
        if (!enabled) return false;
        return start.isBefore(end)
                ? !now.isBefore(start) && now.isBefore(end)   // 13h–15h
                : !now.isBefore(start) || now.isBefore(end);  // 22h–7h: atravessa a meia-noite
    }
}
