package com.kage.card;

import com.kage.AbstractIntegrationTest;
import com.kage.card.application.usecase.IssueCard;
import com.kage.card.application.usecase.RegisterPurchase;
import com.kage.card.domain.entity.Invoice;
import com.kage.card.domain.repository.CardRepository;
import com.kage.card.domain.repository.InvoiceRepository;
import com.kage.shared.domain.valueobject.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Teste de concorrência real pra RegisterPurchase — não mockado, contra Postgres de verdade.
 *
 * A checagem de purchaseId duplicado do RegisterPurchase é em memória, contra o snapshot da
 * fatura que a própria chamada carregou — protege reenvios SEQUENCIAIS (ver
 * RegisterPurchaseIdempotencyIntegrationTest), mas não uma corrida de verdade: se duas
 * requisições lerem a MESMA fatura ANTES de qualquer uma gravar, as duas passam pela checagem em
 * memória (nenhuma vê o item que a outra ainda vai adicionar).
 *
 * Quem pega essa corrida, na prática, é o @Version de InvoiceJpaEntity — não a constraint UNIQUE
 * (invoice_id, purchase_id) da migração V6, como se poderia imaginar à primeira vista.
 * Invoice.addItem() atualiza o próprio updatedAt da fatura, então gravar a fatura depois de
 * adicionar um item sempre reescreve a linha de invoices, não só insere em invoice_items — e
 * esse UPDATE é otimisticamente travado pelo @Version. A segunda gravação (com a versão antiga)
 * falha logo nesse UPDATE, com ObjectOptimisticLockingFailureException, antes mesmo de o
 * Hibernate tentar inserir o invoice_item duplicado. A constraint UNIQUE segue como rede de
 * segurança pra um cenário em que a linha da fatura não fosse regravada junto (não acontece hoje,
 * dado como addItem()/save() estão acoplados), mas quem intercepta de fato, hoje, é o @Version.
 *
 * Duas threads reais (Thread.start()/join()) tornariam o teste não-determinístico: se uma
 * "requisição" terminasse de gravar antes da outra sequer ler, a checagem em memória pegaria o
 * duplicado sozinha e nem o @Version nem a constraint seriam exercitados — mesmo motivo que levou
 * o OptimisticLockingIntegrationTest a simular a corrida em vez de usar threads de verdade. Aqui a
 * simulação é: carregar a MESMA fatura duas vezes (duas cópias em memória, nenhuma vendo a compra
 * da outra) e gravar as duas em sequência — reproduz de forma determinística o exato estado de
 * corrida que o @Version existe pra pegar, toda vez que o teste roda.
 *
 * A fatura precisa já existir ANTES da simulação: se fosse a primeira compra do mês, cada
 * "requisição" abriria sua própria fatura (UUIDs diferentes, versões independentes) e nem o
 * @Version nem a constraint entrariam em jogo.
 */
class RegisterPurchaseConcurrencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    IssueCard issueCard;

    @Autowired
    RegisterPurchase registerPurchase;

    @Autowired
    CardRepository cardRepository;

    @Autowired
    InvoiceRepository invoiceRepository;

    @Test
    void save_deveLancarObjectOptimisticLockingFailureException_quandoDuasRequisicoesLeemAMesmaFaturaSemOItemDaOutra() {
        var card = issueCard.execute(new IssueCard.Input(
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("1000.00"), 10, 20));
        LocalDateTime purchasedAt = LocalDateTime.of(2026, 9, 5, 12, 0);

        // abre a fatura do mês com uma compra prévia, pra garantir que as duas "requisições"
        // disputem o MESMO invoice_id
        var opening = registerPurchase.execute(new RegisterPurchase.Input(
                card.cardId(), UUID.randomUUID(), "Compra inicial", new BigDecimal("50.00"), purchasedAt));

        UUID racedPurchaseId = UUID.randomUUID();

        // duas "requisições" concorrentes leem a MESMA fatura antes de qualquer uma delas gravar
        Invoice requestA = invoiceRepository.findById(opening.invoiceId()).orElseThrow();
        Invoice requestB = invoiceRepository.findById(opening.invoiceId()).orElseThrow();

        requestA.addItem(racedPurchaseId, "Compra concorrente A", Money.of("100.00"), purchasedAt);
        invoiceRepository.save(requestA); // primeira grava, insere o invoice_item sem problema

        requestB.addItem(racedPurchaseId, "Compra concorrente B", Money.of("100.00"), purchasedAt); // ainda não viu o item de A

        assertThatThrownBy(() -> invoiceRepository.save(requestB))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        // efeito da requisição vencedora (A) preservado, B não lançou de novo
        var invoiceAfter = invoiceRepository.findById(opening.invoiceId()).orElseThrow();
        assertThat(invoiceAfter.getItems()).hasSize(2); // compra inicial + a vencedora da corrida
        assertThat(invoiceAfter.total().amount()).isEqualByComparingTo("150.00");
    }
}
