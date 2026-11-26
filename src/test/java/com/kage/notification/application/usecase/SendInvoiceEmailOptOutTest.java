package com.kage.notification.application.usecase;

import com.kage.notification.domain.repository.EmailLogRepository;
import com.kage.notification.domain.repository.EmailLogRepository.EmailLog;
import com.kage.notification.domain.repository.EmailLogRepository.Status;
import com.kage.notification.domain.repository.NotificationPreferenceRepository;
import com.kage.notification.domain.service.EmailChannel;
import com.kage.notification.domain.service.InvoicePdfProvider;
import com.kage.notification.domain.service.RecipientDirectory;
import com.kage.shared.domain.exception.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SendInvoiceEmailOptOutTest {

    @Mock RecipientDirectory recipients;
    @Mock InvoicePdfProvider pdfs;
    @Mock EmailChannel channel;
    @Mock EmailLogRepository logRepository;
    @Mock NotificationPreferenceRepository preferences;

    private final UUID cardId = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();
    private SendInvoiceEmail sendInvoiceEmail;

    @BeforeEach
    void setUp() {
        sendInvoiceEmail = new SendInvoiceEmail(recipients, pdfs, channel, logRepository, Clock.systemUTC(), preferences);
    }

    private SendInvoiceEmail.Input input() {
        return new SendInvoiceEmail.Input(cardId, UUID.randomUUID(), "2026-09", new BigDecimal("1500.00"),
                LocalDate.of(2026, 9, 20), "evt-1");
    }

    @Test
    void desligada_naoGeraPdfNemEnvia_masRegistraOMotivo() {
        when(logRepository.findBySourceEventId("evt-1")).thenReturn(Optional.empty());
        when(recipients.customerOfCard(cardId)).thenReturn(Optional.of(customerId));
        when(recipients.emailOf(customerId)).thenReturn(Optional.of("joao@exemplo.com"));
        when(preferences.invoiceEmailEnabled(customerId)).thenReturn(false);

        assertThat(sendInvoiceEmail.execute(input())).isFalse();

        verify(pdfs, never()).pdfOf(any(), any());
        verify(channel, never()).send(any());
        ArgumentCaptor<EmailLog> log = ArgumentCaptor.forClass(EmailLog.class);
        verify(logRepository).insertIfNew(log.capture());
        assertThat(log.getValue().status()).isEqualTo(Status.SKIPPED);
        assertThat(log.getValue().failureReason()).isEqualTo("O cliente optou por não receber a fatura por e-mail");
        assertThat(log.getValue().subject()).startsWith("Sua fatura Kage de 09/2026 fechou");
        assertThat(log.getValue().attachmentName()).isNull();
    }

    @Test
    void reentregaDeUmPulado_naoFazNada() {
        when(logRepository.findBySourceEventId("evt-1")).thenReturn(Optional.of(new EmailLog(UUID.randomUUID(), customerId,
                "INVOICE_CLOSED", "joao@exemplo.com", "assunto", null, null, null, UUID.randomUUID(), "evt-1",
                Status.SKIPPED, "optou", LocalDateTime.now(), null)));

        assertThat(sendInvoiceEmail.execute(input())).isFalse();

        verify(recipients, never()).customerOfCard(any());
        verify(logRepository, never()).insertIfNew(any());
    }

    @Test
    void configurar_exigeEnabled() {
        NotificationPreferences useCase = new NotificationPreferences(preferences, Clock.systemUTC());

        assertThatThrownBy(() -> useCase.setInvoiceEmail(customerId, null)).isInstanceOf(ValidationException.class);
        assertThat(useCase.setInvoiceEmail(customerId, false).enabled()).isFalse();
        verify(preferences).setInvoiceEmail(eq(customerId), eq(false), any());
    }
}
