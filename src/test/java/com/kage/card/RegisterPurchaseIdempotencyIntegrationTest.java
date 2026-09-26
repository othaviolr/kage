package com.kage.card;

import com.kage.AbstractIntegrationTest;
import com.kage.card.application.usecase.IssueCard;
import com.kage.card.application.usecase.RegisterPurchase;
import com.kage.card.domain.repository.CardRepository;
import com.kage.card.domain.repository.InvoiceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prova a idempotência do RegisterPurchase com reenvio de verdade em transações separadas
 * (não mockado): registra a mesma compra (mesmo purchaseId) duas vezes em sequência, como um
 * cliente reenviando a requisição depois de não receber a resposta da primeira. A checagem em
 * memória (item já presente na fatura carregada) é suficiente pra esse caso sequencial — o caso
 * concorrente de verdade, onde as duas leituras acontecem antes de qualquer gravação, é coberto
 * à parte em RegisterPurchaseConcurrencyIntegrationTest.
 */
class RegisterPurchaseIdempotencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    IssueCard issueCard;

    @Autowired
    RegisterPurchase registerPurchase;

    @Autowired
    CardRepository cardRepository;

    @Autowired
    InvoiceRepository invoiceRepository;

    @Test
    void execute_naoDeveDebitarNemLancarDeNovo_quandoMesmoPurchaseIdReenviadoSequencialmente() {
        var card = issueCard.execute(new IssueCard.Input(
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("1000.00"), 10, 20));
        UUID purchaseId = UUID.randomUUID();
        LocalDateTime purchasedAt = LocalDateTime.of(2026, 9, 5, 12, 0);

        var first = registerPurchase.execute(new RegisterPurchase.Input(
                card.cardId(), purchaseId, "Mercado", new BigDecimal("150.00"), purchasedAt));
        assertThat(first.duplicate()).isFalse();

        var retry = registerPurchase.execute(new RegisterPurchase.Input(
                card.cardId(), purchaseId, "Mercado", new BigDecimal("150.00"), purchasedAt));
        assertThat(retry.duplicate()).isTrue();
        assertThat(retry.invoiceId()).isEqualTo(first.invoiceId());

        var cardAfter = cardRepository.findById(card.cardId()).orElseThrow();
        assertThat(cardAfter.getUsedLimit().amount()).isEqualByComparingTo("150.00"); // não debitou de novo

        var invoiceAfter = invoiceRepository.findById(first.invoiceId()).orElseThrow();
        assertThat(invoiceAfter.getItems()).hasSize(1); // não lançou de novo
    }
}
