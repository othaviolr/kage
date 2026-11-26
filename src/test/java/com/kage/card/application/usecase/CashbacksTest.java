package com.kage.card.application.usecase;

import com.kage.card.domain.entity.Card;
import com.kage.card.domain.entity.Invoice;
import com.kage.card.domain.enums.CashbackDestination;
import com.kage.card.domain.enums.InvoiceItemKind;
import com.kage.card.domain.repository.CardRepository;
import com.kage.card.domain.repository.CashbackAccountCreditRepository;
import com.kage.card.domain.repository.DisputeRepository;
import com.kage.card.domain.repository.InvoiceRepository;
import com.kage.card.domain.service.CardBillingAccountService;
import com.kage.shared.domain.DomainTime;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.ValidationException;
import com.kage.shared.domain.valueobject.Money;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Para onde vai o cashback: conta (quando ativa) ou fatura seguinte; sem crédito em dobro. */
@ExtendWith(MockitoExtension.class)
class CashbacksTest {

    @Mock CardRepository cardRepository;
    @Mock InvoiceRepository invoiceRepository;
    @Mock DisputeRepository disputeRepository;
    @Mock CardEventPublisher events;
    @Mock CashbackAccountCreditRepository accountCredits;
    @Mock CardBillingAccountService account;

    Cashbacks cashbacks;
    Card card;
    Invoice paid;

    @BeforeEach
    void setUp() {
        ZoneId zone = ZoneId.systemDefault();
        DomainTime.use(Clock.fixed(LocalDate.of(2034, 1, 18).atTime(10, 0).atZone(zone).toInstant(), zone));
        cashbacks = new Cashbacks(cardRepository, invoiceRepository, disputeRepository, events, accountCredits, account);
        card = Card.create(UUID.randomUUID(), UUID.randomUUID(), Money.of("5000.00"), 10, 20);
        paid = Invoice.open(card.getId(), YearMonth.of(2034, 1), 10, 20);
        paid.addItem(UUID.randomUUID(), "Mercado", Money.of("1000.00"), LocalDateTime.of(2034, 1, 5, 10, 0));
        paid.close();
        paid.pay();
        lenient().when(disputeRepository.findByCardId(card.getId())).thenReturn(List.of());
        lenient().when(invoiceRepository.findByCardId(card.getId())).thenReturn(List.of(paid));
        lenient().when(accountCredits.findByCardId(card.getId())).thenReturn(List.of());
        lenient().when(invoiceRepository.findByCardIdAndReferenceMonth(card.getId(), YearMonth.of(2034, 2)))
                .thenReturn(Optional.empty());
    }

    @AfterEach
    void resetClock() {
        DomainTime.reset();
    }

    @Test
    void naConta_creditaALancamentoERegistra_semMexerNaFatura() {
        card.chooseCashbackDestination(CashbackDestination.ACCOUNT);
        when(account.creditCashback(card.getAccountId(), Money.of("10.00"), paid.getId())).thenReturn(true);

        assertThat(cashbacks.creditFor(card, paid)).isEqualTo(Money.of("10.00"));

        ArgumentCaptor<CashbackAccountCreditRepository.Credit> credit = ArgumentCaptor.forClass(CashbackAccountCreditRepository.Credit.class);
        verify(accountCredits).insert(credit.capture());
        assertThat(credit.getValue().sourceInvoiceId()).isEqualTo(paid.getId());
        assertThat(credit.getValue().amount()).isEqualTo(Money.of("10.00"));
        verify(invoiceRepository, never()).save(any());
        verify(events).publishCashbackEarned(card.getId(), "2034-01", Money.of("10.00"), "ACCOUNT");
    }

    @Test
    void contaQueNaoRecebe_vaiParaAFatura() {
        card.chooseCashbackDestination(CashbackDestination.ACCOUNT);
        when(account.creditCashback(any(), any(), any())).thenReturn(false); // conta bloqueada/encerrada

        cashbacks.creditFor(card, paid);

        ArgumentCaptor<Invoice> saved = ArgumentCaptor.forClass(Invoice.class);
        verify(invoiceRepository).save(saved.capture());
        assertThat(saved.getValue().getReferenceMonth()).isEqualTo(YearMonth.of(2034, 2));
        assertThat(saved.getValue().getItems()).singleElement()
                .satisfies(i -> assertThat(i.kind()).isEqualTo(InvoiceItemKind.CASHBACK));
        verify(accountCredits, never()).insert(any());
        verify(events).publishCashbackEarned(card.getId(), "2034-01", Money.of("10.00"), "INVOICE");
    }

    @Test
    void naFatura_naoTocaNaConta() {
        cashbacks.creditFor(card, paid);

        verify(account, never()).creditCashback(any(), any(), any());
        verify(invoiceRepository).save(any());
        verify(events).publishCashbackEarned(eq(card.getId()), eq("2034-01"), eq(Money.of("10.00")), eq("INVOICE"));
    }

    @Test
    void jaCreditadoNaConta_naoCreditaDeNovo_nemNaFatura() {
        card.chooseCashbackDestination(CashbackDestination.ACCOUNT);
        when(accountCredits.findByCardId(card.getId())).thenReturn(List.of(
                new CashbackAccountCreditRepository.Credit(paid.getId(), card.getId(), Money.of("10.00"), LocalDateTime.of(2034, 1, 18, 10, 0))));

        assertThat(cashbacks.creditFor(card, paid).isZero()).isTrue();

        verify(account, never()).creditCashback(any(), any(), any());
        verify(invoiceRepository, never()).save(any());
    }

    @Test
    void destinoInvalido_ouCartaoCancelado() {
        when(cardRepository.findById(card.getId())).thenReturn(Optional.of(card));
        assertThatThrownBy(() -> cashbacks.chooseDestination(card.getId(), "PIX")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> cashbacks.chooseDestination(card.getId(), null)).isInstanceOf(ValidationException.class);

        card.cancel();
        assertThatThrownBy(() -> cashbacks.chooseDestination(card.getId(), "ACCOUNT")).isInstanceOf(BusinessRuleException.class);
    }
}
