package com.kage.card.application.usecase;

import com.kage.card.domain.entity.Card;
import com.kage.card.domain.entity.Invoice;
import com.kage.card.domain.enums.InvoiceStatus;
import com.kage.card.domain.repository.CardRepository;
import com.kage.card.domain.repository.InvoiceRepository;
import com.kage.card.domain.service.CardBillingAccountService;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.exception.ValidationException;
import com.kage.shared.domain.valueobject.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PayInvoiceTest {

    private static final YearMonth MONTH = YearMonth.of(2026, 11);

    @Mock CardRepository cardRepository;
    @Mock InvoiceRepository invoiceRepository;
    @Mock CardBillingAccountService billingAccount;
    @Mock CardEventPublisher eventPublisher;

    @InjectMocks PayInvoice payInvoice;

    Card card;
    Invoice invoice;

    @BeforeEach
    void setUp() {
        card = Card.create(UUID.randomUUID(), UUID.randomUUID(), Money.of("5000.00"), 10, 20);
        card.authorizePurchase(Money.of("1200.00"));
        card.authorizePurchase(Money.of("300.00"));
        invoice = Invoice.open(card.getId(), MONTH, 10, 20);
        invoice.addItem(UUID.randomUUID(), "Mercado", Money.of("1200.00"), LocalDateTime.of(2026, 11, 2, 10, 0));
        invoice.addItem(UUID.randomUUID(), "Farmácia", Money.of("300.00"), LocalDateTime.of(2026, 11, 3, 10, 0));
    }

    private void found() {
        when(cardRepository.findById(card.getId())).thenReturn(Optional.of(card));
        when(invoiceRepository.findByCardIdAndReferenceMonth(card.getId(), MONTH)).thenReturn(Optional.of(invoice));
    }

    @Test
    void execute_deveDebitarContaLiberarLimiteEMarcarComoPaga() {
        invoice.close();
        found();
        when(cardRepository.save(any(Card.class))).thenAnswer(i -> i.getArgument(0));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(i -> i.getArgument(0));

        PayInvoice.Output output = payInvoice.execute(new PayInvoice.Input(card.getId(), "2026-11"));

        assertThat(output.status()).isEqualTo("PAID");
        assertThat(output.amountPaid()).isEqualByComparingTo("1500.00");
        assertThat(output.availableLimit()).isEqualByComparingTo("5000.00");
        assertThat(output.paidAt()).isNotNull();
        verify(billingAccount).debitInvoicePayment(card.getAccountId(), Money.of("1500.00"), invoice.getId());
        verify(eventPublisher).publishInvoicePaid(invoice);
    }

    @Test
    void execute_pagamentoParcial_debitaSoOValor_eManteFaturaFechada() {
        invoice.close();
        found();
        when(cardRepository.save(any(Card.class))).thenAnswer(i -> i.getArgument(0));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(i -> i.getArgument(0));

        PayInvoice.Output output = payInvoice.execute(new PayInvoice.Input(card.getId(), "2026-11", new java.math.BigDecimal("500.00")));

        assertThat(output.status()).isEqualTo("CLOSED");
        assertThat(output.amountPaid()).isEqualByComparingTo("500.00");
        assertThat(output.remaining()).isEqualByComparingTo("1000.00");
        assertThat(output.availableLimit()).isEqualByComparingTo("4000.00"); // 5000 - 1500 + 500
        verify(billingAccount).debitInvoicePayment(card.getAccountId(), Money.of("500.00"), invoice.getId());
        verify(eventPublisher).publishInvoicePartiallyPaid(invoice, Money.of("500.00"));
        verify(eventPublisher, never()).publishInvoicePaid(any());
    }

    @Test
    void execute_naoDeveDebitar_quandoValorMaiorQueOSaldo() {
        invoice.close();
        found();

        assertThatThrownBy(() -> payInvoice.execute(new PayInvoice.Input(card.getId(), "2026-11", new java.math.BigDecimal("1500.01"))))
                .isInstanceOf(BusinessRuleException.class);
        verifyNoInteractions(billingAccount);
    }

    @Test
    void execute_naoDeveDebitar_quandoFaturaAindaAberta() {
        found();

        assertThatThrownBy(() -> payInvoice.execute(new PayInvoice.Input(card.getId(), "2026-11")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("fechadas");
        verifyNoInteractions(billingAccount);
        verify(invoiceRepository, never()).save(any());
    }

    @Test
    void execute_naoDeveDebitarDeNovo_quandoJaPaga() {
        invoice.close();
        invoice.pay();
        found();

        assertThatThrownBy(() -> payInvoice.execute(new PayInvoice.Input(card.getId(), "2026-11")))
                .isInstanceOf(BusinessRuleException.class);
        verifyNoInteractions(billingAccount);
    }

    @Test
    void execute_naoDeveLiberarLimiteNemSalvar_quandoContaSemSaldo() {
        invoice.close();
        found();
        doThrow(new BusinessRuleException("Saldo disponível insuficiente"))
                .when(billingAccount).debitInvoicePayment(any(), any(), any());

        assertThatThrownBy(() -> payInvoice.execute(new PayInvoice.Input(card.getId(), "2026-11")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Saldo");
        assertThat(card.availableLimit()).isEqualTo(Money.of("3500.00"));
        verify(cardRepository, never()).save(any());
        verify(invoiceRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void execute_devePagarSemDebitar_quandoFaturaZerada() {
        Invoice empty = Invoice.open(card.getId(), MONTH, 10, 20);
        empty.close();
        when(cardRepository.findById(card.getId())).thenReturn(Optional.of(card));
        when(invoiceRepository.findByCardIdAndReferenceMonth(card.getId(), MONTH)).thenReturn(Optional.of(empty));
        when(cardRepository.save(any(Card.class))).thenAnswer(i -> i.getArgument(0));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(i -> i.getArgument(0));

        PayInvoice.Output output = payInvoice.execute(new PayInvoice.Input(card.getId(), "2026-11"));

        assertThat(output.status()).isEqualTo(InvoiceStatus.PAID.name());
        verifyNoInteractions(billingAccount);
    }

    @Test
    void execute_deveLancarNotFound_quandoCartaoNaoExiste() {
        UUID cardId = UUID.randomUUID();
        when(cardRepository.findById(cardId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> payInvoice.execute(new PayInvoice.Input(cardId, "2026-11")))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void execute_deveLancarValidation_quandoMesInvalido() {
        assertThatThrownBy(() -> payInvoice.execute(new PayInvoice.Input(card.getId(), "novembro")))
                .isInstanceOf(ValidationException.class);
        verifyNoInteractions(cardRepository, invoiceRepository, billingAccount);
    }
}
