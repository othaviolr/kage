package com.kage.card.application.usecase;

import com.kage.card.domain.entity.Card;
import com.kage.card.domain.entity.Invoice;
import com.kage.card.domain.repository.CardRepository;
import com.kage.card.domain.repository.InvoiceRepository;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RegisterPurchaseTest {

    @Mock
    CardRepository cardRepository;

    @Mock
    InvoiceRepository invoiceRepository;

    @InjectMocks
    RegisterPurchase registerPurchase;

    private Card card;
    private final LocalDateTime purchasedAt = LocalDateTime.of(2026, 9, 5, 12, 0);

    @BeforeEach
    void setUp() {
        card = Card.create(UUID.randomUUID(), UUID.randomUUID(), Money.of("1000.00"), 10, 20);
    }

    @Test
    void execute_deveAutorizarCompraEAbrirFatura_quandoNaoExisteFaturaDoMes() {
        when(cardRepository.findById(card.getId())).thenReturn(Optional.of(card));
        when(invoiceRepository.findByCardIdAndReferenceMonth(card.getId(), YearMonth.of(2026, 9)))
                .thenReturn(Optional.empty());
        when(cardRepository.save(any(Card.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RegisterPurchase.Output output = registerPurchase.execute(new RegisterPurchase.Input(
                card.getId(), UUID.randomUUID(), "Mercado", new BigDecimal("150.00"), purchasedAt));

        assertThat(output.availableLimit()).isEqualByComparingTo("850.00");
        assertThat(output.invoiceTotal()).isEqualByComparingTo("150.00");
        assertThat(output.referenceMonth()).isEqualTo(YearMonth.of(2026, 9).toString());
        assertThat(output.duplicate()).isFalse();
    }

    @Test
    void execute_deveLancarNaFaturaExistente_quandoJaHaFaturaAbertaNoMes() {
        Invoice existingInvoice = Invoice.open(card.getId(), YearMonth.of(2026, 9), 10, 20);
        existingInvoice.addItem(UUID.randomUUID(), "Compra anterior", Money.of("50.00"), purchasedAt);

        when(cardRepository.findById(card.getId())).thenReturn(Optional.of(card));
        when(invoiceRepository.findByCardIdAndReferenceMonth(card.getId(), YearMonth.of(2026, 9)))
                .thenReturn(Optional.of(existingInvoice));
        when(cardRepository.save(any(Card.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RegisterPurchase.Output output = registerPurchase.execute(new RegisterPurchase.Input(
                card.getId(), UUID.randomUUID(), "Farmácia", new BigDecimal("30.00"), purchasedAt));

        assertThat(output.invoiceTotal()).isEqualByComparingTo("80.00");
    }

    @Test
    void execute_naoDeveDebitarLimiteDeNovo_quandoPurchaseIdJaLancado() {
        UUID purchaseId = UUID.randomUUID();
        Invoice existingInvoice = Invoice.open(card.getId(), YearMonth.of(2026, 9), 10, 20);
        existingInvoice.addItem(purchaseId, "Mercado", Money.of("150.00"), purchasedAt);
        card.authorizePurchase(Money.of("150.00")); // efeito já aplicado na tentativa anterior

        when(cardRepository.findById(card.getId())).thenReturn(Optional.of(card));
        when(invoiceRepository.findByCardIdAndReferenceMonth(card.getId(), YearMonth.of(2026, 9)))
                .thenReturn(Optional.of(existingInvoice));

        RegisterPurchase.Output output = registerPurchase.execute(new RegisterPurchase.Input(
                card.getId(), purchaseId, "Mercado", new BigDecimal("150.00"), purchasedAt));

        assertThat(output.duplicate()).isTrue();
        assertThat(output.availableLimit()).isEqualByComparingTo("850.00"); // não debitou de novo
        verify(cardRepository, never()).save(any());
        verify(invoiceRepository, never()).save(any());
    }

    @Test
    void execute_deveLancarBusinessRuleException_quandoExcedeLimiteDisponivel() {
        when(cardRepository.findById(card.getId())).thenReturn(Optional.of(card));
        when(invoiceRepository.findByCardIdAndReferenceMonth(card.getId(), YearMonth.of(2026, 9)))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> registerPurchase.execute(new RegisterPurchase.Input(
                card.getId(), UUID.randomUUID(), "Compra grande", new BigDecimal("5000.00"), purchasedAt)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Limite disponível insuficiente");

        verify(invoiceRepository, never()).save(any());
    }

    @Test
    void execute_deveLancarBusinessRuleException_quandoCartaoBloqueado() {
        card.block();
        when(cardRepository.findById(card.getId())).thenReturn(Optional.of(card));
        when(invoiceRepository.findByCardIdAndReferenceMonth(card.getId(), YearMonth.of(2026, 9)))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> registerPurchase.execute(new RegisterPurchase.Input(
                card.getId(), UUID.randomUUID(), "Compra", new BigDecimal("10.00"), purchasedAt)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("não está ativo");
    }

    @Test
    void execute_deveLancarNotFoundException_quandoCartaoNaoExiste() {
        UUID cardId = UUID.randomUUID();
        when(cardRepository.findById(cardId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> registerPurchase.execute(new RegisterPurchase.Input(
                cardId, UUID.randomUUID(), "Compra", new BigDecimal("10.00"), purchasedAt)))
                .isInstanceOf(NotFoundException.class);

        verifyNoInteractions(invoiceRepository);
    }

    @Test
    void execute_deveLancarValidationException_quandoValorZeroOuNegativo() {
        assertThatThrownBy(() -> registerPurchase.execute(new RegisterPurchase.Input(
                card.getId(), UUID.randomUUID(), "Compra", BigDecimal.ZERO, purchasedAt)))
                .isInstanceOf(ValidationException.class);

        verifyNoInteractions(cardRepository, invoiceRepository);
    }

    @Test
    void execute_deveRotearParaFaturaDoMesSeguinte_quandoCompraDepoisDoFechamento() {
        LocalDateTime afterClosing = LocalDateTime.of(2026, 9, 15, 9, 0); // fechamento é dia 10

        when(cardRepository.findById(card.getId())).thenReturn(Optional.of(card));
        when(invoiceRepository.findByCardIdAndReferenceMonth(card.getId(), YearMonth.of(2026, 10)))
                .thenReturn(Optional.empty());
        when(cardRepository.save(any(Card.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RegisterPurchase.Output output = registerPurchase.execute(new RegisterPurchase.Input(
                card.getId(), UUID.randomUUID(), "Compra tardia", new BigDecimal("20.00"), afterClosing));

        assertThat(output.referenceMonth()).isEqualTo(YearMonth.of(2026, 10).toString());
    }
}
