package com.kage.loan;

import com.kage.AbstractIntegrationTest;
import com.kage.account.application.usecase.DepositAccount;
import com.kage.loan.application.usecase.LoanDischargeLetters;
import com.kage.loan.application.usecase.PersonalLoans;
import com.kage.shared.domain.DomainTime;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.NotFoundException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Carta de quitação com Postgres real: só de empréstimo (ou acordo) quitado. */
class LoanDischargeLetterIntegrationTest extends AbstractIntegrationTest {

    @Autowired PersonalLoans personalLoans;
    @Autowired LoanDischargeLetters dischargeLetters;
    @Autowired DepositAccount depositAccount;
    @Autowired JdbcTemplate jdbcTemplate;

    @AfterEach
    void resetClock() {
        DomainTime.reset();
    }

    private static void today(LocalDate date) {
        ZoneId zone = ZoneId.systemDefault();
        DomainTime.use(Clock.fixed(date.atTime(10, 0).atZone(zone).toInstant(), zone));
    }

    private record Setup(UUID customerId, UUID accountId) {}

    private Setup customer() {
        today(LocalDate.of(2035, 3, 1));
        UUID customerId = newActiveCustomerId();
        UUID accountId = newAccountIdFor(customerId);
        depositAccount.execute(new DepositAccount.Input(accountId, new BigDecimal("6000.00")));
        return new Setup(customerId, accountId);
    }

    private static String text(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private String cpfOf(UUID customerId) {
        String cpf = jdbcTemplate.queryForObject("SELECT cpf FROM customers WHERE id = ?", String.class, customerId);
        return cpf.replaceFirst("(\\d{3})(\\d{3})(\\d{3})(\\d{2})", "$1.$2.$3-$4");
    }

    @Test
    void quitado_temCarta_comOQueFoiPagoEAutenticacao() throws IOException {
        Setup s = customer();
        PersonalLoans.Output loan = personalLoans.contract(s.accountId(), new BigDecimal("3000.00"), 6, 10);
        today(LocalDate.of(2035, 4, 10));
        BigDecimal first = personalLoans.payNext(s.accountId(), loan.loanId()).amount();
        today(LocalDate.of(2035, 5, 2));
        BigDecimal payoff = personalLoans.payOff(s.accountId(), loan.loanId()).amount();

        LoanDischargeLetters.Letter letter = dischargeLetters.letter(s.accountId(), loan.loanId());
        assertThat(letter.agreement()).isFalse();
        assertThat(letter.holderCpf()).isEqualTo(cpfOf(s.customerId()));
        assertThat(letter.contractDate()).isEqualTo(LocalDate.of(2035, 3, 1));
        assertThat(letter.financed()).isEqualByComparingTo(loan.financed());
        assertThat(letter.installments()).isEqualTo(6);
        assertThat(letter.paidInInstallments()).isEqualByComparingTo(first.add(payoff));
        assertThat(letter.paidOffAt().toLocalDate()).isEqualTo(LocalDate.of(2035, 5, 2));
        assertThat(letter.downPayment()).isNull();
        // o mesmo contrato gera o mesmo código (o banco consegue conferir)
        assertThat(dischargeLetters.letter(s.accountId(), loan.loanId()).authenticationCode())
                .isEqualTo(letter.authenticationCode()).matches("[0-9A-F]{4}(-[0-9A-F]{4}){7}");

        LoanDischargeLetters.Document pdf = dischargeLetters.pdf(s.accountId(), loan.loanId());
        assertThat(pdf.fileName()).isEqualTo("carta-de-quitacao-kage-2035-05-02.pdf");
        assertThat(new String(pdf.content(), 0, 5)).isEqualTo("%PDF-");
        assertThat(text(pdf.content()).replace("\n", " ").replace("\r", ""))
                .contains("Carta de quitação")
                .contains("o empréstimo pessoal contratado em 01/03/2035,")
                .contains("CPF " + cpfOf(s.customerId()))
                .contains("foi integralmente quitado em 02/05/2035")
                .contains("não restando nenhum valor em aberto referente a este contrato.")
                .contains("Autenticação: " + letter.authenticationCode());
    }

    @Test
    void ativo_cancelado_eRenegociado_naoTemCarta_eOutraConta404() {
        Setup s = customer();
        PersonalLoans.Output cancelled = personalLoans.contract(s.accountId(), new BigDecimal("1000.00"), 3, 10);
        today(LocalDate.of(2035, 3, 5));
        personalLoans.withdraw(s.accountId(), cancelled.loanId());
        assertThatThrownBy(() -> dischargeLetters.letter(s.accountId(), cancelled.loanId()))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("não houve dívida");

        PersonalLoans.Output active = personalLoans.contract(s.accountId(), new BigDecimal("1000.00"), 3, 10);
        assertThatThrownBy(() -> dischargeLetters.letter(s.accountId(), active.loanId()))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("parcelas em aberto");

        UUID otherAccount = newAccountIdFor(newActiveCustomerId());
        assertThatThrownBy(() -> dischargeLetters.letter(otherAccount, active.loanId())).isInstanceOf(NotFoundException.class);

        // atrasa e renegocia: o original não tem carta (a dívida está no acordo)
        today(LocalDate.of(2035, 5, 20));
        personalLoans.renegotiate(s.accountId(), active.loanId(), 3, 10, null);
        assertThatThrownBy(() -> dischargeLetters.letter(s.accountId(), active.loanId()))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("quando o acordo for quitado");
    }

    @Test
    void acordoQuitado_cobreTambemOEmprestimoRenegociado() throws IOException {
        Setup s = customer();
        PersonalLoans.Output loan = personalLoans.contract(s.accountId(), new BigDecimal("2000.00"), 6, 10);
        today(LocalDate.of(2035, 5, 20)); // abril e maio vencidos
        PersonalLoans.Output agreement = personalLoans.renegotiate(s.accountId(), loan.loanId(), 6, 10,
                new BigDecimal("200.00"));
        today(LocalDate.of(2035, 6, 1));
        personalLoans.payOff(s.accountId(), agreement.loanId());

        LoanDischargeLetters.Letter letter = dischargeLetters.letter(s.accountId(), agreement.loanId());
        assertThat(letter.agreement()).isTrue();
        assertThat(letter.contractDate()).isEqualTo(LocalDate.of(2035, 5, 20));
        assertThat(letter.originalContractDate()).isEqualTo(LocalDate.of(2035, 3, 1));
        assertThat(letter.downPayment()).isEqualByComparingTo("200.00");

        String text = text(dischargeLetters.pdf(s.accountId(), agreement.loanId()).content()).replace("\n", " ").replace("\r", "");
        assertThat(text)
                .contains("o acordo de renegociação firmado em 20/05/2035, que substituiu o empréstimo pessoal contratado em 01/03/2035,")
                .contains("referente ao acordo nem ao empréstimo renegociado.")
                .contains("Entrada do acordo")
                .contains("R$ 200,00");
    }
}
