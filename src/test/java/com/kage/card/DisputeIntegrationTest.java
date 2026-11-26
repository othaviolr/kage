package com.kage.card;

import com.kage.AbstractIntegrationTest;
import com.kage.account.application.usecase.DepositAccount;
import com.kage.card.application.usecase.CloseInvoice;
import com.kage.card.application.usecase.DisputePurchase;
import com.kage.card.application.usecase.GetCard;
import com.kage.card.application.usecase.GetInvoice;
import com.kage.card.application.usecase.IssueCard;
import com.kage.card.application.usecase.ListInvoices;
import com.kage.card.application.usecase.PayInvoice;
import com.kage.card.application.usecase.RegisterPurchase;
import com.kage.shared.domain.DomainTime;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.ConflictException;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.exception.ValidationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contestação de compra com Postgres real. Cartão de 5000, fecha dia 10 e vence dia 20; datas em
 * 2035, com o relógio do domínio fixado no "hoje" de cada passo.
 */
class DisputeIntegrationTest extends AbstractIntegrationTest {

    @Autowired IssueCard issueCard;
    @Autowired RegisterPurchase registerPurchase;
    @Autowired CloseInvoice closeInvoice;
    @Autowired PayInvoice payInvoice;
    @Autowired DepositAccount depositAccount;
    @Autowired DisputePurchase disputePurchase;
    @Autowired GetInvoice getInvoice;
    @Autowired ListInvoices listInvoices;
    @Autowired GetCard getCard;

    @AfterEach
    void resetClock() {
        DomainTime.reset();
    }

    private static void today(int month, int day) {
        ZoneId zone = ZoneId.systemDefault();
        DomainTime.use(Clock.fixed(LocalDateTime.of(2035, month, day, 10, 0).atZone(zone).toInstant(), zone));
    }

    private record Setup(UUID cardId, UUID accountId) {}

    private Setup card() {
        IssueCard.Input input = issueCardInput(new BigDecimal("5000.00"), 10, 20);
        return new Setup(issueCard.execute(input).cardId(), input.accountId());
    }

    private UUID buy(UUID cardId, String description, String amount, int month, int day, int installments) {
        UUID purchaseId = UUID.randomUUID();
        registerPurchase.execute(new RegisterPurchase.Input(cardId, purchaseId, description, new BigDecimal(amount),
                LocalDateTime.of(2035, month, day, 10, 0), installments));
        return purchaseId;
    }

    private GetInvoice.Output invoice(UUID cardId, String month) {
        return getInvoice.execute(new GetInvoice.Input(cardId, month));
    }

    private BigDecimal availableLimit(UUID cardId) {
        return getCard.execute(new GetCard.Input(cardId)).availableLimit();
    }

    private java.util.List<String> months(UUID cardId) {
        return listInvoices.execute(new ListInvoices.Input(cardId)).invoices().stream()
                .map(i -> i.referenceMonth()).toList();
    }

    @Test
    void parcelada_parcelasFuturasSaem_eOJaPagoViraCredito_negadaVoltaACobrar() {
        Setup s = card();
        today(3, 5);
        UUID notebook = buy(s.cardId(), "Notebook", "1200.00", 3, 5, 3); // 400 em 03, 04 e 05
        closeInvoice.execute(new CloseInvoice.Input(s.cardId(), "2035-03"));
        depositAccount.execute(new DepositAccount.Input(s.accountId(), new BigDecimal("2000.00")));
        payInvoice.execute(new PayInvoice.Input(s.cardId(), "2035-03"));
        assertThat(availableLimit(s.cardId())).isEqualByComparingTo("4200.00");

        today(3, 15); // depois do corte: a fatura atual é a de abril
        DisputePurchase.Output opened = disputePurchase.open(new DisputePurchase.Input(
                s.cardId(), notebook, "NOT_RECEIVED", "Paguei e o notebook não chegou"));

        assertThat(opened.status()).isEqualTo("OPEN");
        assertThat(opened.description()).isEqualTo("Notebook");
        assertThat(opened.amount()).isEqualByComparingTo("1200.00");
        assertThat(opened.creditedAmount()).isEqualByComparingTo("400.00");
        assertThat(opened.creditMonth()).isEqualTo("2035-04");
        assertThat(opened.cardBlocked()).isFalse();

        // as parcelas de abril e maio saíram (maio ficou vazia e sumiu); a 1ª, já paga, volta como crédito.
        // Abril também tem o cashback de março (1% dos 400, paga em dia ANTES da contestação existir)
        assertThat(months(s.cardId())).containsExactlyInAnyOrder("2035-03", "2035-04");
        GetInvoice.Output april = invoice(s.cardId(), "2035-04");
        assertThat(april.items()).hasSize(2);
        assertThat(april.items()).anySatisfy(item -> {
            assertThat(item.kind()).isEqualTo("DISPUTE_CREDIT");
            assertThat(item.amount()).isEqualByComparingTo("400.00");
        });
        assertThat(april.items()).anySatisfy(item -> {
            assertThat(item.kind()).isEqualTo("CASHBACK");
            assertThat(item.amount()).isEqualByComparingTo("4.00");
        });
        assertThat(april.total()).isEqualByComparingTo("0.00");
        assertThat(availableLimit(s.cardId())).isEqualByComparingTo("5000.00"); // as parcelas suspensas devolveram limite

        // uma compra nova é abatida pelo crédito; o que sobra vai para maio no fechamento
        buy(s.cardId(), "Padaria", "100.00", 3, 20, 1);
        assertThat(invoice(s.cardId(), "2035-04").total()).isEqualByComparingTo("0.00");
        closeInvoice.execute(new CloseInvoice.Input(s.cardId(), "2035-04"));

        GetInvoice.Output closedApril = invoice(s.cardId(), "2035-04");
        assertThat(closedApril.status()).isEqualTo("PAID"); // o crédito pagou tudo
        assertThat(availableLimit(s.cardId())).isEqualByComparingTo("5000.00");
        GetInvoice.Output may = invoice(s.cardId(), "2035-05");
        assertThat(may.items()).singleElement().satisfies(item -> {
            assertThat(item.kind()).isEqualTo("CREDIT_BALANCE");
            assertThat(item.amount()).isEqualByComparingTo("304.00"); // 400 + 4 de cashback - 100 da padaria
        });

        // negada: a compra inteira volta, à vista, na fatura atual (maio)
        today(4, 12);
        DisputePurchase.Output rejected = disputePurchase.resolve(s.cardId(), opened.disputeId(), false,
                "Entrega comprovada pelo estabelecimento");

        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(rejected.reversalMonth()).isEqualTo("2035-05");
        GetInvoice.Output mayAfter = invoice(s.cardId(), "2035-05");
        assertThat(mayAfter.items()).anySatisfy(item -> {
            assertThat(item.kind()).isEqualTo("DISPUTE_REVERSAL");
            assertThat(item.amount()).isEqualByComparingTo("1200.00");
        });
        assertThat(mayAfter.total()).isEqualByComparingTo("896.00"); // 1200 - 304 de saldo credor
        // o cliente pagou 400 e deve 896: 1300 (notebook + padaria) - 4 de cashback da parcela paga em dia
        closeInvoice.execute(new CloseInvoice.Input(s.cardId(), "2035-05"));
        payInvoice.execute(new PayInvoice.Input(s.cardId(), "2035-05"));
        assertThat(availableLimit(s.cardId())).isEqualByComparingTo("5000.00");

        assertThatThrownBy(() -> disputePurchase.resolve(s.cardId(), opened.disputeId(), true, null))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void naoReconhecida_bloqueiaOCartao_eAceitaMantemOCredito() {
        Setup s = card();
        today(3, 6);
        buy(s.cardId(), "Mercado", "50.00", 3, 5, 1);
        UUID fraud = buy(s.cardId(), "Loja desconhecida", "300.00", 3, 5, 1);

        DisputePurchase.Output opened = disputePurchase.open(new DisputePurchase.Input(s.cardId(), fraud, "not_recognized", null));

        assertThat(opened.cardBlocked()).isTrue();
        assertThat(opened.creditedAmount()).isEqualByComparingTo("0.00");
        assertThat(opened.creditMonth()).isNull();
        assertThat(getCard.execute(new GetCard.Input(s.cardId())).status()).isEqualTo("BLOCKED");
        assertThat(invoice(s.cardId(), "2035-03").total()).isEqualByComparingTo("50.00");
        assertThat(availableLimit(s.cardId())).isEqualByComparingTo("4950.00");

        DisputePurchase.Output accepted = disputePurchase.resolve(s.cardId(), opened.disputeId(), true, "Fraude confirmada");
        assertThat(accepted.status()).isEqualTo("ACCEPTED");
        assertThat(invoice(s.cardId(), "2035-03").total()).isEqualByComparingTo("50.00");
        assertThat(disputePurchase.list(s.cardId())).singleElement()
                .satisfies(d -> assertThat(d.status()).isEqualTo("ACCEPTED"));
    }

    @Test
    void regras_prazoDuplicidadeEValidacoes() {
        Setup s = card();
        today(3, 6);
        UUID purchase = buy(s.cardId(), "Loja", "80.00", 3, 5, 1);

        assertThatThrownBy(() -> disputePurchase.open(new DisputePurchase.Input(s.cardId(), UUID.randomUUID(), "NOT_RECEIVED", "x")))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> disputePurchase.open(new DisputePurchase.Input(s.cardId(), purchase, "NOT_RECEIVED", null)))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> disputePurchase.open(new DisputePurchase.Input(s.cardId(), purchase, "OUTRO", "x")))
                .isInstanceOf(ValidationException.class);

        today(7, 4); // 121 dias depois da compra
        assertThatThrownBy(() -> disputePurchase.open(new DisputePurchase.Input(s.cardId(), purchase, "DUPLICATE_CHARGE", "Cobrou duas vezes")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("120 dias");

        today(7, 3); // 120 dias: ainda dá
        DisputePurchase.Output opened = disputePurchase.open(new DisputePurchase.Input(s.cardId(), purchase, "DUPLICATE_CHARGE", "Cobrou duas vezes"));
        assertThatThrownBy(() -> disputePurchase.open(new DisputePurchase.Input(s.cardId(), purchase, "DUPLICATE_CHARGE", "de novo")))
                .isInstanceOf(ConflictException.class);

        assertThatThrownBy(() -> disputePurchase.resolve(s.cardId(), opened.disputeId(), false, null))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> disputePurchase.get(card().cardId(), opened.disputeId()))
                .isInstanceOf(NotFoundException.class); // contestação de outro cartão
    }
}
