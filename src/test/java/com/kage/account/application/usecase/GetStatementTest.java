package com.kage.account.application.usecase;

import com.kage.account.domain.entity.Account;
import com.kage.account.domain.entity.AccountEntry;
import com.kage.account.domain.enums.AccountType;
import com.kage.account.domain.enums.EntryType;
import com.kage.account.domain.repository.AccountEntryRepository;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.shared.domain.exception.ValidationException;
import com.kage.shared.domain.valueobject.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetStatementTest {

    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 25);

    @Mock
    AccountRepository accountRepository;

    @Mock
    AccountEntryRepository entryRepository;

    GetStatement getStatement;
    Account account;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(TODAY.atTime(10, 0).atZone(ZONE).toInstant(), ZONE);
        getStatement = new GetStatement(accountRepository, entryRepository, clock);
        account = Account.create(UUID.randomUUID(), AccountType.CHECKING, "00001", "1");
        account.credit(Money.of("70.00"));
    }

    @Test
    void execute_deveUsarUltimos30DiasEDevolverDebitosNegativos_quandoSemDatas() {
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        UUID pixId = UUID.randomUUID();
        when(entryRepository.findByAccountIdBetween(account.getId(),
                TODAY.minusDays(29).atStartOfDay(), TODAY.plusDays(1).atStartOfDay()))
                .thenReturn(List.of(
                        new AccountEntry(UUID.randomUUID(), account.getId(), EntryType.PIX_SENT, Money.of("30.00"),
                                Money.of("70.00"), pixId, LocalDateTime.of(2026, 11, 24, 9, 0)),
                        new AccountEntry(UUID.randomUUID(), account.getId(), EntryType.DEPOSIT, Money.of("100.00"),
                                Money.of("100.00"), null, LocalDateTime.of(2026, 11, 20, 9, 0))));

        GetStatement.Output output = getStatement.execute(new GetStatement.Input(account.getId(), null, null));

        assertThat(output.from()).isEqualTo(TODAY.minusDays(29));
        assertThat(output.to()).isEqualTo(TODAY);
        assertThat(output.currentBalance()).isEqualByComparingTo("70.00");
        assertThat(output.entries()).extracting(GetStatement.EntryOutput::amount)
                .usingElementComparator(java.math.BigDecimal::compareTo)
                .containsExactly(new java.math.BigDecimal("-30.00"), new java.math.BigDecimal("100.00"));
        assertThat(output.entries().get(0).referenceId()).isEqualTo(pixId);
        assertThat(output.entries().get(0).description()).isEqualTo("PIX enviado");
        // conta sem movimento antes do período: começa do zero e termina no saldo do último lançamento
        assertThat(output.openingBalance()).isEqualByComparingTo("0.00");
        assertThat(output.closingBalance()).isEqualByComparingTo("70.00");
    }

    @Test
    void execute_saldoInicial_eODoUltimoLancamentoAntesDoPeriodo() {
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        LocalDate from = TODAY.minusDays(5);
        when(entryRepository.balanceBefore(account.getId(), from.atStartOfDay())).thenReturn(Optional.of(Money.of("250.00")));
        when(entryRepository.findByAccountIdBetween(account.getId(), from.atStartOfDay(), TODAY.plusDays(1).atStartOfDay()))
                .thenReturn(List.of());

        GetStatement.Output output = getStatement.execute(new GetStatement.Input(account.getId(), from, TODAY));

        assertThat(output.openingBalance()).isEqualByComparingTo("250.00");
        assertThat(output.closingBalance()).isEqualByComparingTo("250.00"); // sem movimento no período
    }

    @Test
    void exportar_aceitaUmAnoInteiro_masNaoMais() {
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 12, 31);
        when(entryRepository.findByAccountIdBetween(account.getId(), from.atStartOfDay(), to.plusDays(1).atStartOfDay()))
                .thenReturn(List.of());

        assertThat(getStatement.forExport(new GetStatement.Input(account.getId(), from, to)).entries()).isEmpty();
        assertThatThrownBy(() -> getStatement.forExport(new GetStatement.Input(account.getId(), from, to.plusDays(2))))
                .isInstanceOf(ValidationException.class).hasMessageContaining("366 dias");
        assertThatThrownBy(() -> getStatement.execute(new GetStatement.Input(account.getId(), from, to)))
                .hasMessageContaining("90 dias"); // na tela continua 90
    }

    @Test
    void execute_deveRecusar_quandoDataInicialDepoisDaFinal() {
        assertThatThrownBy(() -> getStatement.execute(new GetStatement.Input(account.getId(), TODAY, TODAY.minusDays(1))))
                .isInstanceOf(ValidationException.class);
        verifyNoInteractions(accountRepository, entryRepository);
    }

    @Test
    void execute_deveRecusar_quandoPeriodoMaiorQue90Dias() {
        assertThatThrownBy(() -> getStatement.execute(new GetStatement.Input(account.getId(), TODAY.minusDays(90), TODAY)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("90 dias");
    }
}
