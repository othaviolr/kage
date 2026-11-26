package com.kage.notification;

import com.kage.AbstractIntegrationTest;
import com.kage.card.application.usecase.CloseInvoice;
import com.kage.card.application.usecase.IssueCard;
import com.kage.card.application.usecase.RegisterPurchase;
import com.kage.notification.application.usecase.NotificationPreferences;
import com.kage.notification.application.usecase.SendInvoiceEmail;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Fechamento da fatura → evento → fila do e-mail → PDF anexo → registro do envio. */
class InvoiceEmailIntegrationTest extends AbstractIntegrationTest {

    @Autowired IssueCard issueCard;
    @Autowired RegisterPurchase registerPurchase;
    @Autowired CloseInvoice closeInvoice;
    @Autowired SendInvoiceEmail sendInvoiceEmail;
    @Autowired NotificationPreferences notificationPreferences;

    @Test
    void fechamento_mandaAFaturaPorEmailComOPdf() {
        IssueCard.Input input = issueCardInput(new BigDecimal("1000.00"), 10, 20);
        UUID cardId = issueCard.execute(input).cardId();
        registerPurchase.execute(new RegisterPurchase.Input(cardId, UUID.randomUUID(), "Mercado", new BigDecimal("80.00"),
                LocalDateTime.of(2032, 7, 3, 10, 0)));

        closeInvoice.execute(new CloseInvoice.Input(cardId, "2032-07"));

        awaitUntil(() -> sendInvoiceEmail.sentTo(input.customerId()).stream().anyMatch(e -> e.status().equals("SENT")),
                Duration.ofSeconds(15));
        List<SendInvoiceEmail.Item> sent = sendInvoiceEmail.sentTo(input.customerId());
        assertThat(sent).singleElement().satisfies(e -> {
            assertThat(e.kind()).isEqualTo("INVOICE_CLOSED");
            assertThat(e.to()).endsWith("@teste.com");
            assertThat(e.subject()).isEqualTo("Sua fatura Kage de 07/2032 fechou: R$ 80,00, vence em 20/07/2032");
            assertThat(e.attachmentName()).isEqualTo("fatura-kage-2032-07.pdf");
            assertThat(e.attachmentSize()).isGreaterThan(1000);
            assertThat(e.attachmentSha256()).matches("[0-9a-f]{64}");
            assertThat(e.sentAt()).isNotNull();
        });
    }

    @Test
    void clienteDesligouAFaturaPorEmail_ficaRegistradoComoPulado() {
        IssueCard.Input input = issueCardInput(new BigDecimal("1000.00"), 10, 20);
        UUID cardId = issueCard.execute(input).cardId();
        notificationPreferences.setInvoiceEmail(input.customerId(), false);
        registerPurchase.execute(new RegisterPurchase.Input(cardId, UUID.randomUUID(), "Mercado", new BigDecimal("80.00"),
                LocalDateTime.of(2032, 8, 3, 10, 0)));

        closeInvoice.execute(new CloseInvoice.Input(cardId, "2032-08"));

        awaitUntil(() -> !sendInvoiceEmail.sentTo(input.customerId()).isEmpty(), Duration.ofSeconds(15));
        assertThat(sendInvoiceEmail.sentTo(input.customerId())).singleElement().satisfies(e -> {
            assertThat(e.status()).isEqualTo("SKIPPED");
            assertThat(e.failureReason()).contains("optou por não receber");
            assertThat(e.attachmentName()).isNull();
            assertThat(e.sentAt()).isNull();
        });
        assertThat(notificationPreferences.invoiceEmail(input.customerId()).enabled()).isFalse();
    }
}
