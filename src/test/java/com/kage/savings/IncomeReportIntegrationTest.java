package com.kage.savings;

import com.jayway.jsonpath.JsonPath;
import com.kage.AbstractIntegrationTest;
import com.kage.account.application.usecase.CreateAccount;
import com.kage.account.application.usecase.DepositAccount;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.customer.application.usecase.approvekyc.ApproveKycInput;
import com.kage.customer.application.usecase.approvekyc.ApproveKycUseCase;
import com.kage.savings.application.usecase.RunSavingsYield;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Informe de rendimentos com um ano de história de verdade: as operações acontecem hoje pelo HTTP e
 * depois todos os registros do cliente voltam um ano no tempo.
 */
class IncomeReportIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Sombra-2026";

    @Autowired WebApplicationContext context;
    @Autowired ApproveKycUseCase approveKyc;
    @Autowired CreateAccount createAccount;
    @Autowired DepositAccount depositAccount;
    @Autowired AccountRepository accountRepository;
    @Autowired RunSavingsYield runSavingsYield;
    @Autowired JdbcTemplate jdbcTemplate;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private record Customer(UUID id, String token, UUID accountId, String cpf) {}

    private Customer customerWith(String balance) throws Exception {
        String cpf = randomCpf();
        String body = mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content("""
                        {"fullName":"Cliente Informe","cpf":"%s","email":"%s@teste.com","phone":"41999991234",
                         "birthDate":"1990-01-01","street":"Rua A","number":"1","city":"Curitiba","state":"PR",
                         "zipCode":"80000-000","password":"%s"}""".formatted(cpf, UUID.randomUUID(), PASSWORD)))
                .andReturn().getResponse().getContentAsString();
        UUID customerId = UUID.fromString(JsonPath.read(body, "$.customerId"));
        approveKyc.execute(new ApproveKycInput(customerId));
        UUID accountId = createAccount.execute(new CreateAccount.Input(customerId, "CHECKING")).accountId();
        depositAccount.execute(new DepositAccount.Input(accountId, new BigDecimal(balance)));
        String login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cpf\":\"%s\",\"password\":\"%s\"}".formatted(cpf, PASSWORD)))
                .andReturn().getResponse().getContentAsString();
        return new Customer(customerId, JsonPath.read(login, "$.accessToken"), accountId, cpf);
    }

    private String postAs(Customer c, String path, String body) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                        .header("Authorization", "Bearer " + c.token()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
    }

    private static BigDecimal number(String json, String path) {
        return new BigDecimal(JsonPath.read(json, path).toString());
    }

    /** Leva todos os registros do cliente {@code interval} para trás. */
    private void shiftBack(Customer c, UUID box, String interval) {
        jdbcTemplate.update("UPDATE accounts SET opened_at = opened_at - ?::interval WHERE id = ?", interval, c.accountId());
        jdbcTemplate.update("UPDATE account_entries SET created_at = created_at - ?::interval WHERE account_id = ?", interval, c.accountId());
        jdbcTemplate.update("UPDATE savings_boxes SET created_at = created_at - ?::interval WHERE id = ?", interval, box);
        jdbcTemplate.update("UPDATE savings_box_movements SET created_at = created_at - ?::interval WHERE box_id = ?", interval, box);
    }

    @Test
    void informeDoAnoPassado_trazSaldosEmDezembro_eRendimentoRealizadoComIrRetido() throws Exception {
        Customer ana = customerWith("3000.00");
        UUID box = UUID.fromString(JsonPath.read(
                postAs(ana, "/api/accounts/" + ana.accountId() + "/savings-boxes", "{\"name\":\"Reserva\"}"), "$.id"));
        postAs(ana, "/api/savings-boxes/" + box + "/deposit", "{\"amount\":2000.00}");

        // o dinheiro fica 200 dias aplicado (IR de 20%) e uma parte é resgatada
        jdbcTemplate.update("UPDATE savings_box_lots SET deposited_on = deposited_on - 200 WHERE box_id = ?", box);
        jdbcTemplate.update("UPDATE savings_boxes SET last_yield_date = last_yield_date - 200 WHERE id = ?", box);
        runSavingsYield.execute(LocalDate.now());
        String withdrawal = postAs(ana, "/api/savings-boxes/" + box + "/withdraw", "{\"amount\":1000.00}");
        BigDecimal gain = number(withdrawal, "$.withdrawal.gain");
        BigDecimal incomeTax = number(withdrawal, "$.withdrawal.incomeTax");
        assertThat(incomeTax).isEqualByComparingTo(gain.multiply(new BigDecimal("0.20")).setScale(2, java.math.RoundingMode.DOWN));

        BigDecimal accountNow = accountRepository.findById(ana.accountId()).orElseThrow().getBalance().amount();
        BigDecimal boxNow = jdbcTemplate.queryForObject("SELECT balance FROM savings_boxes WHERE id = ?", BigDecimal.class, box);
        shiftBack(ana, box, "1 year");
        int lastYear = LocalDate.now().getYear() - 1;

        String report = mvc.perform(get("/api/customers/" + ana.id() + "/income-report?year=" + lastYear)
                        .header("Authorization", "Bearer " + ana.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.year").value(lastYear))
                .andExpect(jsonPath("$.partial").value(false))
                .andExpect(jsonPath("$.holderName").value("Cliente Informe"))
                .andExpect(jsonPath("$.yearEnd").value(lastYear + "-12-31"))
                .andExpect(jsonPath("$.accounts.length()").value(1))
                .andExpect(jsonPath("$.accounts[0].balancePreviousYearEnd").value(0))
                .andExpect(jsonPath("$.accounts[0].balanceYearEnd").value(accountNow.doubleValue()))
                .andExpect(jsonPath("$.savingsBoxes.length()").value(1))
                .andExpect(jsonPath("$.savingsBoxes[0].balancePreviousYearEnd").value(0))
                .andExpect(jsonPath("$.savingsBoxes[0].balanceYearEnd").value(boxNow.doubleValue()))
                .andExpect(jsonPath("$.savingsBoxes[0].realizedGain").value(gain.doubleValue()))
                .andExpect(jsonPath("$.savingsBoxes[0].incomeTaxWithheld").value(incomeTax.doubleValue()))
                .andExpect(jsonPath("$.totals.exclusiveTaxationIncome").value(gain.subtract(incomeTax).doubleValue()))
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(report, "$.holderCpf"))
                .isEqualTo(ana.cpf().replaceFirst("(\\d{3})(\\d{3})(\\d{3})(\\d{2})", "$1.$2.$3-$4"));
        assertThat((String) JsonPath.read(report, "$.guide.exclusiveTaxation")).contains(gain.subtract(incomeTax).toPlainString());

        // o ano corrente parte dos saldos de 31/12 do ano passado e ainda não tem resgate
        mvc.perform(get("/api/customers/" + ana.id() + "/income-report?year=" + (lastYear + 1))
                        .header("Authorization", "Bearer " + ana.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partial").value(true))
                .andExpect(jsonPath("$.accounts[0].balancePreviousYearEnd").value(accountNow.doubleValue()))
                .andExpect(jsonPath("$.savingsBoxes[0].balancePreviousYearEnd").value(boxNow.doubleValue()))
                .andExpect(jsonPath("$.savingsBoxes[0].realizedGain").value(0))
                .andExpect(jsonPath("$.totals.incomeTaxWithheld").value(0));
    }

    @Test
    void semAno_eOAnoPassado() throws Exception {
        Customer leo = customerWith("10.00");
        mvc.perform(get("/api/customers/" + leo.id() + "/income-report").header("Authorization", "Bearer " + leo.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.year").value(LocalDate.now().getYear() - 1))
                .andExpect(jsonPath("$.accounts.length()").value(0)); // a conta foi aberta este ano
    }

    @Test
    void anoInvalido_400_eInformeDeOutroCliente_404() throws Exception {
        Customer dono = customerWith("10.00");
        Customer curioso = customerWith("10.00");

        mvc.perform(get("/api/customers/" + dono.id() + "/income-report?year=1999").header("Authorization", "Bearer " + dono.token()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/customers/" + dono.id() + "/income-report?year=" + (LocalDate.now().getYear() + 1))
                        .header("Authorization", "Bearer " + dono.token()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/customers/" + dono.id() + "/income-report").header("Authorization", "Bearer " + curioso.token()))
                .andExpect(status().isNotFound());
    }

    private static String randomCpf() {
        int[] d = new int[11];
        do {
            for (int i = 0; i < 9; i++) d[i] = ThreadLocalRandom.current().nextInt(10);
        } while (d[0] == d[1] && d[1] == d[2]);
        d[9] = checkDigit(d, 9);
        d[10] = checkDigit(d, 10);
        StringBuilder cpf = new StringBuilder();
        for (int digit : d) cpf.append(digit);
        return cpf.toString();
    }

    private static int checkDigit(int[] d, int length) {
        int sum = 0;
        for (int i = 0; i < length; i++) sum += d[i] * (length + 1 - i);
        int rest = (sum * 10) % 11;
        return rest == 10 ? 0 : rest;
    }
}
