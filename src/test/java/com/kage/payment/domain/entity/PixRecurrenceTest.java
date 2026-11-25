package com.kage.payment.domain.entity;

import com.kage.payment.domain.entity.PixRecurrence.Frequency;
import com.kage.payment.domain.entity.PixRecurrence.Status;
import com.kage.shared.domain.exception.DomainException;
import com.kage.shared.domain.valueobject.Money;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PixRecurrenceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 25);
    private static final LocalDateTime NOW = TODAY.atTime(10, 0);

    private PixRecurrence recurrence(Frequency frequency, LocalDate start, int occurrences) {
        return PixRecurrence.create(UUID.randomUUID(), "chave", Money.of("1500.00"), "aluguel", frequency, start,
                occurrences, TODAY, NOW);
    }

    @Test
    void mensal_deveManterODiaDoInicio_mesmoDepoisDeUmMesCurto() {
        PixRecurrence r = recurrence(Frequency.MONTHLY, LocalDate.of(2027, 1, 31), 4);

        assertThat(r.dateOf(1)).isEqualTo(LocalDate.of(2027, 1, 31));
        assertThat(r.dateOf(2)).isEqualTo(LocalDate.of(2027, 2, 28)); // fevereiro não tem 31
        assertThat(r.dateOf(3)).isEqualTo(LocalDate.of(2027, 3, 31)); // volta ao 31, não fica no 28
        assertThat(r.dateOf(4)).isEqualTo(LocalDate.of(2027, 4, 30));
    }

    @Test
    void semanal_deveCairNoMesmoDiaDaSemana() {
        PixRecurrence r = recurrence(Frequency.WEEKLY, LocalDate.of(2026, 11, 27), 3); // sexta

        assertThat(r.dateOf(2)).isEqualTo(LocalDate.of(2026, 12, 4));
        assertThat(r.dateOf(3)).isEqualTo(LocalDate.of(2026, 12, 11));
        assertThatThrownBy(() -> r.dateOf(4)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void criar_deveValidarInicioQuantidadeEValor() {
        assertThatThrownBy(() -> recurrence(Frequency.MONTHLY, TODAY, 3))
                .isInstanceOf(DomainException.class).hasMessageContaining("data futura");
        assertThatThrownBy(() -> recurrence(Frequency.MONTHLY, TODAY.plusDays(366), 3))
                .isInstanceOf(DomainException.class).hasMessageContaining("365");
        assertThatThrownBy(() -> recurrence(Frequency.MONTHLY, TODAY.plusDays(1), 1))
                .isInstanceOf(DomainException.class).hasMessageContaining("de 2 a 60");
        assertThatThrownBy(() -> recurrence(Frequency.MONTHLY, TODAY.plusDays(1), 61))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> recurrence(Frequency.WEEKLY, TODAY.plusDays(1), 53))
                .isInstanceOf(DomainException.class).hasMessageContaining("de 2 a 52");
        assertThatThrownBy(() -> recurrence(null, TODAY.plusDays(1), 3))
                .isInstanceOf(DomainException.class).hasMessageContaining("Frequência");
        assertThatThrownBy(() -> PixRecurrence.create(UUID.randomUUID(), "chave", Money.ZERO, null, Frequency.MONTHLY,
                TODAY.plusDays(1), 3, TODAY, NOW)).isInstanceOf(DomainException.class);

        assertThat(recurrence(Frequency.MONTHLY, TODAY.plusDays(1), 60).getStatus()).isEqualTo(Status.ACTIVE);
    }

    @Test
    void avancar_deveLiberarUmaOcorrenciaPorVez_eEncerrarDepoisDaUltima() {
        PixRecurrence r = recurrence(Frequency.MONTHLY, TODAY.plusDays(5), 2);

        assertThat(r.advance(NOW).getAsInt()).isEqualTo(1);
        assertThat(r.advance(NOW).getAsInt()).isEqualTo(2);
        assertThat(r.getStatus()).isEqualTo(Status.ACTIVE); // a 2ª ainda vai acontecer

        assertThat(r.advance(NOW)).isEmpty(); // a 2ª foi resolvida
        assertThat(r.getStatus()).isEqualTo(Status.FINISHED);
    }

    @Test
    void cancelada_naoAnda_eNaoCancelaDeNovo() {
        PixRecurrence r = recurrence(Frequency.WEEKLY, TODAY.plusDays(5), 4);
        r.advance(NOW);

        r.cancel(NOW);

        assertThat(r.getStatus()).isEqualTo(Status.CANCELLED);
        assertThat(r.advance(NOW)).isEmpty();
        assertThatThrownBy(() -> r.cancel(NOW)).isInstanceOf(DomainException.class);
    }

    @Test
    void ocorrencia_eUmPixAgendadoComum_ligadoARecorrencia() {
        PixRecurrence r = recurrence(Frequency.MONTHLY, LocalDate.of(2027, 1, 31), 3);

        PixTransaction second = PixTransaction.occurrenceOf(r, 2);

        assertThat(second.isScheduled()).isTrue();
        assertThat(second.isRecurring()).isTrue();
        assertThat(second.getRecurrenceId()).isEqualTo(r.getId());
        assertThat(second.getOccurrenceNumber()).isEqualTo(2);
        assertThat(second.getScheduledDate()).isEqualTo(LocalDate.of(2027, 2, 28).atStartOfDay());
        assertThat(second.getAmount()).isEqualTo(Money.of("1500.00"));
    }
}
