package com.kage.card;

import com.kage.AbstractIntegrationTest;
import com.kage.card.application.usecase.CardPlastics;
import com.kage.card.application.usecase.CloseInvoice;
import com.kage.card.application.usecase.InvoiceDocuments;
import com.kage.card.application.usecase.IssueCard;
import com.kage.card.application.usecase.RegisterPurchase;
import com.kage.card.application.usecase.VirtualCards;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.NotFoundException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Fatura em PDF a partir de dados reais: titular, plástico, virtual, total e mínimo. */
class InvoicePdfIntegrationTest extends AbstractIntegrationTest {

    @Autowired IssueCard issueCard;
    @Autowired RegisterPurchase registerPurchase;
    @Autowired CloseInvoice closeInvoice;
    @Autowired InvoiceDocuments invoiceDocuments;
    @Autowired CardPlastics cardPlastics;
    @Autowired VirtualCards virtualCards;

    @Test
    void faturaFechada_viraPdfComOsDadosDela() throws Exception {
        UUID cardId = issueCard.execute(issueCardInput(new BigDecimal("2000.00"), 10, 20)).cardId();
        VirtualCards.Details virtual = virtualCards.create(new VirtualCards.Input(cardId, "Streaming", null));
        registerPurchase.execute(new RegisterPurchase.Input(cardId, UUID.randomUUID(), "Supermercado", new BigDecimal("450.00"),
                LocalDateTime.of(2033, 4, 2, 10, 0)));
        registerPurchase.execute(new RegisterPurchase.Input(cardId, UUID.randomUUID(), "Assinatura", new BigDecimal("39.90"),
                LocalDateTime.of(2033, 4, 3, 10, 0), 1, virtual.number()));
        closeInvoice.execute(new CloseInvoice.Input(cardId, "2033-04"));

        InvoiceDocuments.Document document = invoiceDocuments.pdf(cardId, "2033-04");

        assertThat(document.fileName()).isEqualTo("fatura-kage-2033-04.pdf");
        String lastDigits = cardPlastics.list(cardId).get(0).maskedNumber().substring(5);
        try (PDDocument pdf = Loader.loadPDF(document.content())) {
            assertThat(new PDFTextStripper().getText(pdf))
                    .contains("Fatura de 04/2033")
                    .contains("Cliente de Teste")
                    .contains("cartão final " + lastDigits)
                    .contains("R$ 489,90")          // total
                    .contains("R$ 73,49")           // mínimo: 15%
                    .contains("20/04/2033")
                    .contains("Assinatura (virtual Streaming)")
                    .contains("Juros do rotativo")
                    .contains("IOF (rotativo e parcelamento)")
                    .contains("0,38% + 0,0082% ao dia")
                    .contains("CET do rotativo (30 dias)")
                    .contains("CET do parcelamento em 12x");
        }

        // mesma fatura, mesmo código de autenticação
        assertThat(invoiceDocuments.statement(cardId, "2033-04").authenticationCode())
                .isEqualTo(invoiceDocuments.statement(cardId, "2033-04").authenticationCode());
    }

    @Test
    void faturaAberta_naoTemPdf() {
        UUID cardId = issueCard.execute(issueCardInput(new BigDecimal("2000.00"), 10, 20)).cardId();
        registerPurchase.execute(new RegisterPurchase.Input(cardId, UUID.randomUUID(), "Padaria", new BigDecimal("20.00"),
                LocalDateTime.of(2033, 4, 2, 10, 0)));

        assertThatThrownBy(() -> invoiceDocuments.pdf(cardId, "2033-04"))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("ainda aberta");
        assertThatThrownBy(() -> invoiceDocuments.pdf(cardId, "2033-05")).isInstanceOf(NotFoundException.class);
    }
}
