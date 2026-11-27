package com.kage.card;

import com.kage.AbstractIntegrationTest;
import com.kage.card.application.usecase.CardTransactions;
import com.kage.card.application.usecase.CloseInvoice;
import com.kage.card.application.usecase.IssueCard;
import com.kage.card.application.usecase.RegisterPurchase;
import com.kage.card.application.usecase.VirtualCards;
import com.kage.card.infrastructure.export.CardTransactionsCsv;
import com.kage.card.infrastructure.export.CardTransactionsOfx;
import com.kage.shared.domain.DomainTime;
import com.kage.shared.domain.exception.NotFoundException;
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
 * Extrato do cartão com Postgres real. Janeiro/2034: Mercado 600, Notebook 300 em 3x e Streaming 50
 * no cartão virtual; a fatura de janeiro fica sem pagar e vai para o rotativo no fechamento de fevereiro.
 */
class CardTransactionsIntegrationTest extends AbstractIntegrationTest {

    @Autowired IssueCard issueCard;
    @Autowired RegisterPurchase registerPurchase;
    @Autowired VirtualCards virtualCards;
    @Autowired CloseInvoice closeInvoice;
    @Autowired CardTransactions cardTransactions;

    @AfterEach
    void resetClock() {
        DomainTime.reset();
    }

    private static void today(LocalDate date) {
        ZoneId zone = ZoneId.systemDefault();
        DomainTime.use(Clock.fixed(date.atTime(10, 0).atZone(zone).toInstant(), zone));
    }

    private record Setup(UUID cardId, UUID virtualCardId) {}

    private Setup scenario() {
        today(LocalDate.of(2034, 1, 5));
        UUID cardId = issueCard.execute(issueCardInput(new BigDecimal("5000.00"), 10, 20)).cardId();
        VirtualCards.Details streaming = virtualCards.create(new VirtualCards.Input(cardId, "Streaming", null));
        LocalDateTime at = LocalDateTime.of(2034, 1, 5, 10, 0);
        registerPurchase.execute(new RegisterPurchase.Input(cardId, UUID.randomUUID(), "Mercado", new BigDecimal("600.00"), at));
        registerPurchase.execute(new RegisterPurchase.Input(cardId, UUID.randomUUID(), "Notebook", new BigDecimal("300.00"), at, 3));
        registerPurchase.execute(new RegisterPurchase.Input(cardId, UUID.randomUUID(), "Streaming", new BigDecimal("50.00"),
                at, 1, streaming.number()));
        today(LocalDate.of(2034, 1, 11));
        closeInvoice.execute(new CloseInvoice.Input(cardId, "2034-01"));
        today(LocalDate.of(2034, 2, 11));
        closeInvoice.execute(new CloseInvoice.Input(cardId, "2034-02")); // janeiro não foi paga: rotativo
        return new Setup(cardId, streaming.virtualCardId());
    }

    @Test
    void lancamentosDoPeriodo_cadaParcelaNoMesDela_eTotaisPorTipo() {
        Setup s = scenario();

        CardTransactions.Output all = cardTransactions.list(s.cardId(), "2034-01", "2034-03", null, null);

        assertThat(all.groups()).containsExactly("PURCHASES", "FEES", "CREDITS", "BALANCES");
        assertThat(all.lines()).filteredOn(l -> l.description().startsWith("Notebook"))
                .extracting(CardTransactions.Line::invoiceMonth, CardTransactions.Line::installment)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("2034-01", "1/3"),
                        org.assertj.core.groups.Tuple.tuple("2034-02", "2/3"),
                        org.assertj.core.groups.Tuple.tuple("2034-03", "3/3"));
        assertThat(all.lines()).anySatisfy(l -> {
            assertThat(l.kind()).isEqualTo("PREVIOUS_BALANCE");
            assertThat(l.group()).isEqualTo("BALANCES");
            assertThat(l.invoiceMonth()).isEqualTo("2034-02");
        }).anySatisfy(l -> assertThat(l.kind()).isEqualTo("REVOLVING_INTEREST"));
        assertThat(all.totals()).filteredOn(t -> t.group().equals("PURCHASES")).singleElement().satisfies(t -> {
            assertThat(t.count()).isEqualTo(5);
            assertThat(t.amount()).isEqualByComparingTo("950.00");
        });
        assertThat(all.totals()).filteredOn(t -> t.group().equals("FEES")).singleElement()
                .satisfies(t -> assertThat(t.amount()).isPositive());
        assertThat(all.total()).isEqualByComparingTo(all.totals().stream().map(CardTransactions.Total::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    @Test
    void filtros_porTipo_ePorCartaoVirtual() {
        Setup s = scenario();

        CardTransactions.Output spending = cardTransactions.list(s.cardId(), "2034-01", "2034-03", "purchases,fees", null);
        assertThat(spending.groups()).containsExactly("PURCHASES", "FEES");
        assertThat(spending.lines()).noneMatch(l -> l.group().equals("BALANCES"));

        CardTransactions.Output virtual = cardTransactions.list(s.cardId(), "2034-01", "2034-03", null, s.virtualCardId());
        assertThat(virtual.lines()).singleElement().satisfies(l -> {
            assertThat(l.description()).isEqualTo("Streaming");
            assertThat(l.virtualCard()).isEqualTo("Streaming");
        });

        assertThatThrownBy(() -> cardTransactions.list(s.cardId(), null, null, null, UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> cardTransactions.list(s.cardId(), "2034-01", "2035-01", null, null))
                .isInstanceOf(ValidationException.class).hasMessage("Período de até 12 faturas");
        assertThatThrownBy(() -> cardTransactions.list(s.cardId(), "2034-03", "2034-01", null, null))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> cardTransactions.list(s.cardId(), null, null, "SAQUES", null))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void semPeriodo_asUltimasSeisAteAAtual() {
        Setup s = scenario();
        CardTransactions.Output out = cardTransactions.list(s.cardId(), null, null, null, null);
        assertThat(out.toMonth()).isEqualTo("2034-03"); // a aberta mais antiga
        assertThat(out.fromMonth()).isEqualTo("2033-10");
    }

    @Test
    void csv_eOfx() {
        Setup s = scenario();
        CardTransactions.Output out = cardTransactions.list(s.cardId(), "2034-01", "2034-01", "PURCHASES", null);

        String csv = CardTransactionsCsv.write(out);
        assertThat(csv).startsWith("﻿Data;Fatura;Descrição;Parcela;Cartão;Tipo;Categoria;Valor (R$)\r\n")
                .contains("05/01/2034;01/2034;Mercado;;;Compras;Outros;600,00\r\n")
                .contains("05/01/2034;01/2034;Notebook (1/3);1/3;;Compras;Outros;100,00\r\n")
                .contains(";Virtual: Streaming;Compras;Outros;50,00\r\n")
                .endsWith(";;Total do período;;;;;750,00\r\n");
        assertThat(CardTransactionsCsv.fileName(out, "csv")).isEqualTo("extrato-cartao-kage-2034-01-a-2034-01.csv");

        String ofx = CardTransactionsOfx.write(out);
        assertThat(ofx).startsWith("OFXHEADER:100\r\nDATA:OFXSGML\r\nVERSION:102\r\n")
                .contains("<CCSTMTRS><CURDEF>BRL<CCACCTFROM><ACCTID>")
                .contains("<DTSTART>20340101000000[-3:BRT]<DTEND>20340131235959[-3:BRT]")
                .contains("<TRNTYPE>DEBIT<DTPOSTED>20340105100000[-3:BRT]<TRNAMT>-600.00<FITID>")
                .contains("<NAME>Mercado</STMTTRN>")
                .contains("<MEMO>Parcela 1/3</STMTTRN>")
                .contains("<LEDGERBAL><BALAMT>-750.00")
                .endsWith("</OFX>\r\n");
        assertThat(ofx.split("<STMTTRN>", -1)).hasSize(out.lines().size() + 1);
    }

    @Test
    void csv_protegeContraFormula() {
        today(LocalDate.of(2034, 1, 5));
        UUID cardId = issueCard.execute(issueCardInput(new BigDecimal("5000.00"), 10, 20)).cardId();
        registerPurchase.execute(new RegisterPurchase.Input(cardId, UUID.randomUUID(), "=HYPERLINK(\"x\")",
                new BigDecimal("10.00"), LocalDateTime.of(2034, 1, 5, 10, 0)));
        String csv = CardTransactionsCsv.write(cardTransactions.list(cardId, "2034-01", "2034-01", null, null));
        assertThat(csv).contains(";\"'=HYPERLINK(\"\"x\"\")\";");
    }
}
