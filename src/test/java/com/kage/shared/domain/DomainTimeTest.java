package com.kage.shared.domain;

import com.kage.account.domain.entity.Account;
import com.kage.account.domain.enums.AccountType;
import com.kage.card.domain.entity.Invoice;
import com.kage.payment.domain.entity.PixTransaction;
import com.kage.payment.domain.enums.TransactionType;
import com.kage.shared.domain.valueobject.Money;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** As entidades marcam a hora pelo relógio da aplicação, e não pelo do sistema. */
class DomainTimeTest {

    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    private static final LocalDateTime NIGHT = LocalDateTime.of(2026, 11, 25, 22, 30); // janela noturna do PIX

    @BeforeEach
    void fixClock() {
        DomainTime.use(Clock.fixed(NIGHT.atZone(ZONE).toInstant(), ZONE));
    }

    @AfterEach
    void releaseClock() {
        DomainTime.reset();
    }

    @Test
    void pix_processadoNaHoraDoRelogio_eEssaHoraQueOLimiteNoturnoSoma() {
        PixTransaction pix = PixTransaction.create(UUID.randomUUID(), "chave", Money.of("100.00"), null,
                TransactionType.IMMEDIATE, null);
        assertThat(pix.getCreatedAt()).isEqualTo(NIGHT);

        pix.startProcessing(UUID.randomUUID());
        pix.complete();

        assertThat(pix.getProcessedAt()).isEqualTo(NIGHT);
        assertThat(pix.getCompletedAt()).isEqualTo(NIGHT);
    }

    @Test
    void contaEFatura_tambem() {
        Account account = Account.create(UUID.randomUUID(), AccountType.CHECKING, "00001", "1");
        assertThat(account.getOpenedAt()).isEqualTo(NIGHT);

        Invoice invoice = Invoice.open(UUID.randomUUID(), YearMonth.of(2026, 11), 10, 20);
        invoice.close();
        invoice.pay(); // fatura zerada: quitada sem pagamento
        assertThat(invoice.getPaidAt()).isEqualTo(NIGHT);
    }

    @Test
    void reset_voltaAoRelogioDoSistema() {
        DomainTime.reset();

        assertThat(DomainTime.now()).isAfter(NIGHT.minusYears(100)).isNotEqualTo(NIGHT);
        assertThat(DomainTime.clock().getZone()).isEqualTo(ZoneId.systemDefault());
    }
}
