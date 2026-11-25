package com.kage.card;

import com.kage.AbstractIntegrationTest;
import com.kage.account.application.usecase.DepositAccount;
import com.kage.account.application.usecase.GetStatement;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.card.application.usecase.CancelCard;
import com.kage.card.application.usecase.CloseInvoice;
import com.kage.card.application.usecase.GetCard;
import com.kage.card.application.usecase.GetInvoice;
import com.kage.card.application.usecase.IssueCard;
import com.kage.card.application.usecase.PayInvoice;
import com.kage.card.application.usecase.RegisterPurchase;
import com.kage.shared.domain.exception.BusinessRuleException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pagamento de fatura com Postgres real: a fatura vira PAID, a conta é debitada com lançamento no
 * extrato e o limite do cartão volta — tudo ou nada. Sem saldo, nenhum dos três muda.
 */
class CardInvoicePaymentIntegrationTest extends AbstractIntegrationTest {

    private static final LocalDateTime SEPTEMBER = LocalDateTime.of(2026, 9, 5, 10, 0);

    @Autowired IssueCard issueCard;
    @Autowired RegisterPurchase registerPurchase;
    @Autowired CloseInvoice closeInvoice;
    @Autowired PayInvoice payInvoice;
    @Autowired GetInvoice getInvoice;
    @Autowired GetCard getCard;
    @Autowired CancelCard cancelCard;
    @Autowired DepositAccount depositAccount;
    @Autowired GetStatement getStatement;
    @Autowired AccountRepository accountRepository;

    private record Setup(UUID cardId, UUID accountId) {}

    /** Cartão de 1000 com 400 de compras numa fatura de setembro já fechada. */
    private Setup closedInvoiceOf400() {
        IssueCard.Input input = issueCardInput(new BigDecimal("1000.00"), 10, 20);
        UUID cardId = issueCard.execute(input).cardId();
        registerPurchase.execute(new RegisterPurchase.Input(cardId, UUID.randomUUID(), "Mercado", new BigDecimal("250.00"), SEPTEMBER));
        registerPurchase.execute(new RegisterPurchase.Input(cardId, UUID.randomUUID(), "Posto", new BigDecimal("150.00"), SEPTEMBER));
        closeInvoice.execute(new CloseInvoice.Input(cardId, "2026-09"));
        return new Setup(cardId, input.accountId());
    }

    private BigDecimal balanceOf(UUID accountId) {
        return accountRepository.findById(accountId).orElseThrow().getBalance().amount();
    }

    @Test
    void pagar_deveDebitarContaLiberarLimiteERegistrarNoExtrato() {
        Setup s = closedInvoiceOf400();
        depositAccount.execute(new DepositAccount.Input(s.accountId(), new BigDecimal("1000.00")));

        PayInvoice.Output paid = payInvoice.execute(new PayInvoice.Input(s.cardId(), "2026-09"));

        assertThat(paid.status()).isEqualTo("PAID");
        assertThat(paid.amountPaid()).isEqualByComparingTo("400.00");
        assertThat(balanceOf(s.accountId())).isEqualByComparingTo("600.00");
        assertThat(getCard.execute(new GetCard.Input(s.cardId())).availableLimit()).isEqualByComparingTo("1000.00");
        assertThat(getInvoice.execute(new GetInvoice.Input(s.cardId(), "2026-09")).status()).isEqualTo("PAID");

        var entries = getStatement.execute(new GetStatement.Input(s.accountId(), null, null)).entries();
        assertThat(entries).anySatisfy(entry -> {
            assertThat(entry.type()).isEqualTo("INVOICE_PAYMENT");
            assertThat(entry.amount()).isEqualByComparingTo("-400.00");
            assertThat(entry.referenceId()).isEqualTo(paid.invoiceId());
        });

        // sem dívida, o cartão pode ser cancelado
        assertThat(cancelCard.execute(new CancelCard.Input(s.cardId())).status()).isEqualTo("CANCELLED");
    }

    @Test
    void pagar_naoDeveMudarNada_quandoContaSemSaldo() {
        Setup s = closedInvoiceOf400();
        depositAccount.execute(new DepositAccount.Input(s.accountId(), new BigDecimal("100.00")));

        assertThatThrownBy(() -> payInvoice.execute(new PayInvoice.Input(s.cardId(), "2026-09")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Saldo");

        assertThat(balanceOf(s.accountId())).isEqualByComparingTo("100.00");
        assertThat(getInvoice.execute(new GetInvoice.Input(s.cardId(), "2026-09")).status()).isEqualTo("CLOSED");
        assertThat(getCard.execute(new GetCard.Input(s.cardId())).availableLimit()).isEqualByComparingTo("600.00");
    }

    @Test
    void pagarDuasVezes_deveDebitarUmaVezSo() {
        Setup s = closedInvoiceOf400();
        depositAccount.execute(new DepositAccount.Input(s.accountId(), new BigDecimal("1000.00")));
        payInvoice.execute(new PayInvoice.Input(s.cardId(), "2026-09"));

        assertThatThrownBy(() -> payInvoice.execute(new PayInvoice.Input(s.cardId(), "2026-09")))
                .isInstanceOf(BusinessRuleException.class);

        assertThat(balanceOf(s.accountId())).isEqualByComparingTo("600.00");
    }
}
