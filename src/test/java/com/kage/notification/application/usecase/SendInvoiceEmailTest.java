package com.kage.notification.application.usecase;

import com.kage.notification.domain.repository.EmailLogRepository;
import com.kage.notification.domain.repository.EmailLogRepository.EmailLog;
import com.kage.notification.domain.repository.EmailLogRepository.Status;
import com.kage.notification.domain.service.EmailChannel;
import com.kage.notification.domain.service.InvoicePdfProvider;
import com.kage.notification.domain.service.RecipientDirectory;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SendInvoiceEmailTest {

    @Mock RecipientDirectory recipients;
    @Mock InvoicePdfProvider pdfs;
    @Mock EmailChannel channel;
    @Mock EmailLogRepository logRepository;

    private final UUID cardId = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();
    private final byte[] pdf = "%PDF-1.7 fatura".getBytes();
    private SendInvoiceEmail sendInvoiceEmail;

    @BeforeEach
    void setUp() {
        sendInvoiceEmail = new SendInvoiceEmail(recipients, pdfs, channel, logRepository, Clock.systemUTC());
    }

    private SendInvoiceEmail.Input input() {
        return new SendInvoiceEmail.Input(cardId, UUID.randomUUID(), "2026-09", new BigDecimal("1500.00"),
                LocalDate.of(2026, 9, 20), "evt-1");
    }

    private void recipientAndPdf() {
        when(recipients.customerOfCard(cardId)).thenReturn(Optional.of(customerId));
        when(recipients.emailOf(customerId)).thenReturn(Optional.of("joao@exemplo.com"));
        when(pdfs.pdfOf(cardId, "2026-09")).thenReturn(Optional.of(new InvoicePdfProvider.Pdf("fatura-kage-2026-09.pdf", pdf)));
    }

    private EmailLog log(Status status) {
        return new EmailLog(UUID.randomUUID(), customerId, "INVOICE_CLOSED", "joao@exemplo.com", "assunto",
                "fatura-kage-2026-09.pdf", pdf.length, "x".repeat(64), UUID.randomUUID(), "evt-1", status, null,
                LocalDateTime.now(), status == Status.SENT ? LocalDateTime.now() : null);
    }

    @Test
    void enviaComOPdfAnexo_eRegistra() {
        recipientAndPdf();
        when(logRepository.findBySourceEventId("evt-1")).thenReturn(Optional.empty());
        when(logRepository.insertIfNew(any())).thenReturn(true);

        assertThat(sendInvoiceEmail.execute(input())).isTrue();

        ArgumentCaptor<EmailChannel.Email> email = ArgumentCaptor.forClass(EmailChannel.Email.class);
        verify(channel).send(email.capture());
        assertThat(email.getValue().to()).isEqualTo("joao@exemplo.com");
        assertThat(email.getValue().subject()).isEqualTo("Sua fatura Kage de 09/2026 fechou: R$ 1.500,00, vence em 20/09/2026");
        assertThat(email.getValue().body()).contains("nunca pede senha");
        assertThat(email.getValue().attachment().fileName()).isEqualTo("fatura-kage-2026-09.pdf");
        assertThat(email.getValue().attachment().contentType()).isEqualTo("application/pdf");
        assertThat(email.getValue().attachment().content()).isEqualTo(pdf);

        ArgumentCaptor<EmailLog> saved = ArgumentCaptor.forClass(EmailLog.class);
        verify(logRepository).insertIfNew(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo(Status.PENDING);
        assertThat(saved.getValue().attachmentSha256()).hasSize(64);
        verify(logRepository).markSent(eq(saved.getValue().id()), any());
    }

    @Test
    void reentregaDeEventoJaEnviado_naoMandaDeNovo() {
        when(logRepository.findBySourceEventId("evt-1")).thenReturn(Optional.of(log(Status.SENT)));

        assertThat(sendInvoiceEmail.execute(input())).isFalse();
        verify(channel, never()).send(any());
    }

    @Test
    void envioQueFalhouAntes_tentaDeNovo() {
        recipientAndPdf();
        EmailLog failed = log(Status.FAILED);
        when(logRepository.findBySourceEventId("evt-1")).thenReturn(Optional.of(failed));

        assertThat(sendInvoiceEmail.execute(input())).isTrue();

        verify(logRepository, never()).insertIfNew(any());
        verify(channel).send(any());
        verify(logRepository).markSent(eq(failed.id()), any());
    }

    @Test
    void canalFora_marcaFalha_eDevolveAExcecaoParaRetentar() {
        recipientAndPdf();
        when(logRepository.findBySourceEventId("evt-1")).thenReturn(Optional.empty());
        when(logRepository.insertIfNew(any())).thenReturn(true);
        doThrow(new IllegalStateException("SES fora do ar")).when(channel).send(any());

        assertThatThrownBy(() -> sendInvoiceEmail.execute(input())).hasMessage("SES fora do ar");

        verify(logRepository).markFailed(any(), eq("SES fora do ar"));
        verify(logRepository, never()).markSent(any(), any());
    }

    @Test
    void semPdf_naoManda() {
        when(logRepository.findBySourceEventId("evt-1")).thenReturn(Optional.empty());
        when(recipients.customerOfCard(cardId)).thenReturn(Optional.of(customerId));
        when(recipients.emailOf(customerId)).thenReturn(Optional.of("joao@exemplo.com"));
        when(pdfs.pdfOf(cardId, "2026-09")).thenReturn(Optional.empty());

        assertThat(sendInvoiceEmail.execute(input())).isFalse();
        verify(channel, never()).send(any());
        verify(logRepository, never()).insertIfNew(any());
    }
}
