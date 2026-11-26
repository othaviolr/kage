package com.kage.card;

import com.kage.AbstractIntegrationTest;
import com.kage.account.application.usecase.DepositAccount;
import com.kage.card.application.usecase.DisputePurchase;
import com.kage.card.application.usecase.GetInvoice;
import com.kage.card.application.usecase.IssueCard;
import com.kage.card.application.usecase.ListInvoices;
import com.kage.card.application.usecase.PayInvoice;
import com.kage.card.application.usecase.RegisterPurchase;
import com.kage.card.application.usecase.RunBillingCycle;
import com.kage.card.domain.repository.CardRepository;
import com.kage.shared.domain.DomainTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Estorno de juros na contestação procedente, com o rotativo e o parcelamento automático de verdade.
 * Janeiro/2034: compra contestada de 400 + 600 de outras; fecha dia 10, vence dia 20.
 */
class DisputeInterestRefundIntegrationTest extends AbstractIntegrationTest {

    @Autowired IssueCard issueCard;
    @Autowired RegisterPurchase registerPurchase;
    @Autowired RunBillingCycle runBillingCycle;
    @Autowired PayInvoice payInvoice;
    @Autowired DepositAccount depositAccount;
    @Autowired DisputePurchase disputePurchase;
    @Autowired GetInvoice getInvoice;
    @Autowired ListInvoices listInvoices;
    @Autowired CardRepository cardRepository;

    @AfterEach
    void resetClock() {
        DomainTime.reset();
    }

    private static void today(LocalDate date) {
        ZoneId zone = ZoneId.systemDefault();
        DomainTime.use(Clock.fixed(date.atTime(10, 0).atZone(zone).toInstant(), zone));
    }

    private record Setup(UUID cardId, UUID accountId, UUID disputed) {}

    private Setup januaryWithDisputedPurchase() {
        IssueCard.Input input = issueCardInput(new BigDecimal("5000.00"), 10, 20);
        UUID cardId = issueCard.execute(input).cardId();
        depositAccount.execute(new DepositAccount.Input(input.accountId(), new BigDecimal("3000.00")));
        UUID disputed = UUID.randomUUID();
        registerPurchase.execute(new RegisterPurchase.Input(cardId, disputed, "Loja", new BigDecimal("400.00"),
                LocalDateTime.of(2034, 1, 5, 10, 0)));
        registerPurchase.execute(new RegisterPurchase.Input(cardId, UUID.randomUUID(), "Mercado", new BigDecimal("600.00"),
                LocalDateTime.of(2034, 1, 5, 11, 0)));
        runBillingCycle.execute(LocalDate.of(2034, 1, 11)); // fecha janeiro
        return new Setup(cardId, input.accountId(), disputed);
    }

    private BigDecimal itemsOfKind(UUID cardId, String month, String kind) {
        return getInvoice.execute(new GetInvoice.Input(cardId, month)).items().stream()
                .filter(i -> i.kind().equals(kind)).map(GetInvoice.ItemOutput::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private DisputePurchase.Output disputeAndAccept(Setup s, LocalDate when) {
        today(when);
        DisputePurchase.Output opened = disputePurchase.open(new DisputePurchase.Input(
                s.cardId(), s.disputed(), "NOT_RECEIVED", "Comprei e não recebi"));
        return disputePurchase.resolve(s.cardId(), opened.disputeId(), true, "Estabelecimento não comprovou a entrega");
    }

    @Test
    void rotativo_procedenteDevolveOsJurosDaParteDaCompra() {
        Setup s = januaryWithDisputedPurchase();
        payInvoice.execute(new PayInvoice.Input(s.cardId(), "2034-01", new BigDecimal("400.00"))); // sobram 600
        runBillingCycle.execute(LocalDate.of(2034, 2, 11)); // vira: 600 + 84 de juros
        assertThat(itemsOfKind(s.cardId(), "2034-02", "REVOLVING_INTEREST")).isEqualByComparingTo("84.00");
        var chargesBefore = cardRepository.findById(s.cardId()).orElseThrow().getRevolvingCharges().amount();

        DisputePurchase.Output accepted = disputeAndAccept(s, LocalDate.of(2034, 2, 15));

        assertThat(accepted.status()).isEqualTo("ACCEPTED");
        // 400/1000 de (84 de juros + 3,81 de IOF): 33,60 + 1,52
        assertThat(accepted.interestRefunded()).isEqualByComparingTo("35.12");
        // fatura atual (março): o crédito da compra e o estorno dos juros e do IOF
        assertThat(itemsOfKind(s.cardId(), "2034-03", "DISPUTE_CREDIT")).isEqualByComparingTo("400.00");
        assertThat(itemsOfKind(s.cardId(), "2034-03", "DISPUTE_INTEREST_REFUND")).isEqualByComparingTo("35.12");
        // só os juros saem da conta do teto legal: o IOF nunca esteve nela
        assertThat(cardRepository.findById(s.cardId()).orElseThrow().getRevolvingCharges().amount())
                .isEqualByComparingTo(chargesBefore.subtract(new BigDecimal("33.60")));
    }

    @Test
    void rotativoQueVirouParcelamento_devolveTambemOsJurosDasParcelas() {
        Setup s = januaryWithDisputedPurchase();
        runBillingCycle.execute(LocalDate.of(2034, 2, 11)); // não pagou nada: 1000 + 140 + 20 + 7
        runBillingCycle.execute(LocalDate.of(2034, 3, 11)); // não pagou fevereiro: parcelamento automático
        assertThat(getInvoice.execute(new GetInvoice.Input(s.cardId(), "2034-02")).status()).isEqualTo("FINANCED");

        BigDecimal januaryCharges = itemsOfKind(s.cardId(), "2034-02", "REVOLVING_INTEREST")
                .add(itemsOfKind(s.cardId(), "2034-02", "LATE_FINE"))
                .add(itemsOfKind(s.cardId(), "2034-02", "LATE_INTEREST"));
        BigDecimal januaryIof = itemsOfKind(s.cardId(), "2034-02", "IOF");
        BigDecimal planned = BigDecimal.ZERO;
        BigDecimal allIof = BigDecimal.ZERO;
        for (var invoice : listInvoices.execute(new ListInvoices.Input(s.cardId())).invoices()) {
            planned = planned.add(itemsOfKind(s.cardId(), invoice.referenceMonth(), "INVOICE_INSTALLMENT"));
            allIof = allIof.add(itemsOfKind(s.cardId(), invoice.referenceMonth(), "IOF"));
        }
        BigDecimal planIof = allIof.subtract(januaryIof);
        assertThat(januaryIof).isPositive();
        assertThat(planIof).isPositive();
        BigDecimal february = new BigDecimal("1000.00").add(januaryCharges).add(januaryIof);
        // a compra é 40% de janeiro; essa parte (com os encargos e o IOF dela) é 40% de fevereiro também
        BigDecimal ratio = new BigDecimal("0.4");
        BigDecimal charges = januaryCharges.add(planned.subtract(february)).multiply(ratio).setScale(2, RoundingMode.HALF_UP);
        BigDecimal iof = januaryIof.add(planIof).multiply(ratio).setScale(2, RoundingMode.HALF_UP);
        BigDecimal expected = charges.add(iof);

        DisputePurchase.Output accepted = disputeAndAccept(s, LocalDate.of(2034, 3, 15));

        assertThat(accepted.interestRefunded()).isEqualByComparingTo(expected);
        assertThat(accepted.interestRefunded()).isGreaterThan(new BigDecimal("66.80")); // mais que só os encargos de janeiro
    }

    @Test
    void faturaPagaEmDia_procedenteNaoTemJurosParaDevolver() {
        Setup s = januaryWithDisputedPurchase();
        payInvoice.execute(new PayInvoice.Input(s.cardId(), "2034-01"));

        DisputePurchase.Output accepted = disputeAndAccept(s, LocalDate.of(2034, 1, 25));

        assertThat(accepted.interestRefunded()).isEqualByComparingTo("0.00");
        assertThat(itemsOfKind(s.cardId(), "2034-02", "DISPUTE_INTEREST_REFUND")).isEqualByComparingTo("0");
    }
}
