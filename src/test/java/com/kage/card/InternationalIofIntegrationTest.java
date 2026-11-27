package com.kage.card;

import com.kage.AbstractIntegrationTest;
import com.kage.card.application.usecase.CloseInvoice;
import com.kage.card.application.usecase.DisputePurchase;
import com.kage.card.application.usecase.GetCard;
import com.kage.card.application.usecase.GetInvoice;
import com.kage.card.application.usecase.IssueCard;
import com.kage.card.application.usecase.RegisterPurchase;
import com.kage.shared.domain.DomainTime;
import com.kage.shared.domain.exception.ValidationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * IOF da compra internacional na contestação e na compra em reais em estabelecimento de fora.
 * USD 100 na Amazon (cotação fixa de 5,50 + 4%) = R$ 572,00 + R$ 20,02 de IOF; cartão de 5.000.
 */
class InternationalIofIntegrationTest extends AbstractIntegrationTest {

    @Autowired IssueCard issueCard;
    @Autowired RegisterPurchase registerPurchase;
    @Autowired DisputePurchase disputePurchase;
    @Autowired CloseInvoice closeInvoice;
    @Autowired GetInvoice getInvoice;
    @Autowired GetCard getCard;

    @AfterEach
    void resetClock() {
        DomainTime.reset();
    }

    private static void today(LocalDate date) {
        ZoneId zone = ZoneId.systemDefault();
        DomainTime.use(Clock.fixed(date.atTime(10, 0).atZone(zone).toInstant(), zone));
    }

    private record Setup(UUID cardId, UUID purchaseId) {}

    private Setup amazon() {
        today(LocalDate.of(2034, 1, 5));
        UUID cardId = issueCard.execute(issueCardInput(new BigDecimal("5000.00"), 10, 20)).cardId();
        UUID purchaseId = UUID.randomUUID();
        registerPurchase.execute(new RegisterPurchase.Input(cardId, purchaseId, "Amazon", new BigDecimal("100.00"),
                LocalDateTime.of(2034, 1, 5, 10, 0), 1, null, "ONLINE", "US", "USD"));
        return new Setup(cardId, purchaseId);
    }

    private DisputePurchase.Output dispute(Setup s) {
        return disputePurchase.open(new DisputePurchase.Input(s.cardId(), s.purchaseId(), "NOT_RECEIVED",
                "O pedido nunca chegou"));
    }

    private GetInvoice.Output invoice(UUID cardId, String month) {
        return getInvoice.execute(new GetInvoice.Input(cardId, month));
    }

    private BigDecimal available(UUID cardId) {
        return getCard.execute(new GetCard.Input(cardId)).availableLimit();
    }

    @Test
    void naFaturaAberta_oIofSaiComACompra_eVoltaSeAContestacaoForNegada() {
        Setup s = amazon();
        assertThat(available(s.cardId())).isEqualByComparingTo("4407.98");

        DisputePurchase.Output opened = dispute(s);

        assertThat(opened.amount()).isEqualByComparingTo("572.00"); // a compra; o IOF anda à parte
        // a fatura só tinha a compra e o IOF dela: sem os dois, ela some (como em qualquer contestação)
        assertThatThrownBy(() -> invoice(s.cardId(), "2034-01"))
                .isInstanceOf(com.kage.shared.domain.exception.NotFoundException.class);
        assertThat(available(s.cardId())).isEqualByComparingTo("5000.00"); // limite de volta, IOF incluso

        disputePurchase.resolve(s.cardId(), opened.disputeId(), false, "O estabelecimento comprovou a entrega");

        assertThat(invoice(s.cardId(), "2034-01").items()).anySatisfy(i -> {
            assertThat(i.kind()).isEqualTo("DISPUTE_REVERSAL");
            assertThat(i.amount()).isEqualByComparingTo("572.00");
        }).anySatisfy(i -> {
            assertThat(i.kind()).isEqualTo("IOF");
            assertThat(i.description()).isEqualTo("IOF da compra internacional (3,5%): Amazon (USD 100,00 a R$ 5,7200) "
                    + "(contestação negada)");
            assertThat(i.amount()).isEqualByComparingTo("20.02");
        });
        assertThat(available(s.cardId())).isEqualByComparingTo("4407.98");
    }

    @Test
    void naFaturaFechada_oIofVoltaComoCreditoProprio_eFicaSeForProcedente() {
        Setup s = amazon();
        today(LocalDate.of(2034, 1, 11));
        closeInvoice.execute(new CloseInvoice.Input(s.cardId(), "2034-01"));

        DisputePurchase.Output opened = dispute(s);

        assertThat(opened.creditedAmount()).isEqualByComparingTo("572.00");
        assertThat(invoice(s.cardId(), "2034-02").items())
                .anySatisfy(i -> {
                    assertThat(i.kind()).isEqualTo("DISPUTE_CREDIT");
                    assertThat(i.amount()).isEqualByComparingTo("572.00");
                })
                .anySatisfy(i -> {
                    assertThat(i.kind()).isEqualTo("DISPUTE_CREDIT");
                    assertThat(i.description()).startsWith("Crédito de contestação: IOF de Amazon");
                    assertThat(i.amount()).isEqualByComparingTo("20.02");
                });

        DisputePurchase.Output accepted = disputePurchase.resolve(s.cardId(), opened.disputeId(), true, "Procedente");
        assertThat(accepted.status()).isEqualTo("ACCEPTED");
        assertThat(invoice(s.cardId(), "2034-02").items()).filteredOn(i -> i.kind().equals("DISPUTE_CREDIT"))
                .extracting(GetInvoice.ItemOutput::amount)
                .map(BigDecimal::stripTrailingZeros)
                .containsExactlyInAnyOrder(new BigDecimal("572"), new BigDecimal("20.02"));
    }

    @Test
    void emReaisNumEstabelecimentoDeFora_temIof_eEAVista() {
        today(LocalDate.of(2034, 1, 5));
        UUID cardId = issueCard.execute(issueCardInput(new BigDecimal("5000.00"), 10, 20)).cardId();

        RegisterPurchase.Output out = registerPurchase.execute(new RegisterPurchase.Input(cardId, UUID.randomUUID(),
                "Loja chinesa", new BigDecimal("200.00"), LocalDateTime.of(2034, 1, 5, 10, 0), 1, null, "ONLINE", "CN",
                null));

        assertThat(out.international().currency()).isEqualTo("BRL");
        assertThat(out.international().iof()).isEqualByComparingTo("7.00");
        assertThat(out.availableLimit()).isEqualByComparingTo("4793.00");
        assertThat(invoice(cardId, "2034-01").items()).anySatisfy(i -> {
            assertThat(i.kind()).isEqualTo("PURCHASE");
            assertThat(i.description()).isEqualTo("Loja chinesa"); // sem conversão, sem cotação na descrição
        }).anySatisfy(i -> {
            assertThat(i.kind()).isEqualTo("IOF");
            assertThat(i.amount()).isEqualByComparingTo("7.00");
        });

        assertThatThrownBy(() -> registerPurchase.execute(new RegisterPurchase.Input(cardId, UUID.randomUUID(), "Loja",
                new BigDecimal("300.00"), LocalDateTime.of(2034, 1, 5, 10, 0), 3, null, "ONLINE", "CN", null)))
                .isInstanceOf(ValidationException.class).hasMessage("Compra internacional é à vista");

        // no Brasil, nada muda
        RegisterPurchase.Output local = registerPurchase.execute(new RegisterPurchase.Input(cardId, UUID.randomUUID(),
                "Mercado", new BigDecimal("100.00"), LocalDateTime.of(2034, 1, 5, 10, 0), 2, null, "CHIP", "BR", null));
        assertThat(local.international()).isNull();
    }
}
