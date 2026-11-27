package com.kage.card.infrastructure.export;

import com.kage.card.application.usecase.CardTransactions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CardTransactionsOfxTest {

    @Test
    void escapaOsCaracteresDoSgml() {
        assertThat(CardTransactionsOfx.escape("A&B <x>")).isEqualTo("A&amp;B &lt;x&gt;");
    }

    @Test
    void creditoSaiPositivo_eNomeLongoECortadoEm32() {
        LocalDateTime at = LocalDateTime.of(2034, 2, 3, 9, 30);
        CardTransactions.Line cashback = new CardTransactions.Line(UUID.randomUUID(), at, "2034-02",
                "Cashback de 1% da fatura de 01/2034", new BigDecimal("-10.00"), "CASHBACK", "CREDITS", null, null, null);
        CardTransactions.Output out = new CardTransactions.Output(UUID.randomUUID(), "1234", "2034-02", "2034-02",
                List.of("CREDITS"), null, List.of(cashback), List.of(), new BigDecimal("-10.00"), at);

        String ofx = CardTransactionsOfx.write(out);

        assertThat(ofx).contains("<ACCTID>1234</CCACCTFROM>")
                .contains("<TRNTYPE>CREDIT<DTPOSTED>20340203093000[-3:BRT]<TRNAMT>10.00")
                .contains("<NAME>Cashback de 1% da fatura de 01/2</STMTTRN>") // 32 caracteres
                .contains("<BALAMT>10.00");
    }
}
