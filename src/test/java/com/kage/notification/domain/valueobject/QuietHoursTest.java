package com.kage.notification.domain.valueobject;

import com.kage.shared.domain.exception.ValidationException;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuietHoursTest {

    private static LocalTime at(int hour, int minute) {
        return LocalTime.of(hour, minute);
    }

    @Test
    void atravessaAMeiaNoite_22hAs7h() {
        QuietHours q = new QuietHours(true, at(22, 0), at(7, 0));

        assertThat(q.silences(at(21, 59))).isFalse();
        assertThat(q.silences(at(22, 0))).isTrue();   // início inclusive
        assertThat(q.silences(at(23, 59))).isTrue();
        assertThat(q.silences(at(0, 0))).isTrue();
        assertThat(q.silences(at(6, 59))).isTrue();
        assertThat(q.silences(at(7, 0))).isFalse();   // fim exclusive
        assertThat(q.silences(at(12, 0))).isFalse();
    }

    @Test
    void noMesmoDia_13hAs15h() {
        QuietHours q = new QuietHours(true, at(13, 0), at(15, 0));

        assertThat(q.silences(at(12, 59))).isFalse();
        assertThat(q.silences(at(13, 0))).isTrue();
        assertThat(q.silences(at(14, 30))).isTrue();
        assertThat(q.silences(at(15, 0))).isFalse();
        assertThat(q.silences(at(23, 0))).isFalse();
    }

    @Test
    void desligado_naoSilencia() {
        assertThat(QuietHours.DEFAULT.enabled()).isFalse();
        assertThat(QuietHours.DEFAULT.silences(at(23, 0))).isFalse();
    }

    @Test
    void inicioIgualAoFim_ehInvalido_eSegundosSaoIgnorados() {
        assertThatThrownBy(() -> new QuietHours(true, at(22, 0), at(22, 0))).isInstanceOf(ValidationException.class);
        assertThat(new QuietHours(true, LocalTime.of(22, 0, 59), at(7, 0)).start()).isEqualTo(at(22, 0));
    }
}
