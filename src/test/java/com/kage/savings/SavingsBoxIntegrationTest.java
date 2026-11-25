package com.kage.savings;

import com.jayway.jsonpath.JsonPath;
import com.kage.AbstractIntegrationTest;
import com.kage.account.application.usecase.CreateAccount;
import com.kage.account.application.usecase.DepositAccount;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.customer.application.usecase.approvekyc.ApproveKycInput;
import com.kage.customer.application.usecase.approvekyc.ApproveKycUseCase;
import com.kage.notification.application.usecase.ListNotifications;
import com.kage.savings.application.usecase.RunSavingsYield;
import com.kage.savings.domain.service.BusinessCalendar;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Caixinhas pelo HTTP de verdade: guardar/resgatar mexem na conta e na caixinha juntas, e o dinheiro rende. */
class SavingsBoxIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Sombra-2026";

    @Autowired WebApplicationContext context;
    @Autowired ApproveKycUseCase approveKyc;
    @Autowired CreateAccount createAccount;
    @Autowired DepositAccount depositAccount;
    @Autowired AccountRepository accountRepository;
    @Autowired RunSavingsYield runSavingsYield;
    @Autowired ListNotifications listNotifications;
    @Autowired JdbcTemplate jdbcTemplate;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private record Customer(UUID id, String token, UUID accountId) {}

    private Customer customerWith(String balance) throws Exception {
        String cpf = randomCpf();
        String body = mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content("""
                        {"fullName":"Cliente Caixinha","cpf":"%s","email":"%s@teste.com","phone":"41999991234",
                         "birthDate":"1990-01-01","street":"Rua A","number":"1","city":"Curitiba","state":"PR",
                         "zipCode":"80000-000","password":"%s"}""".formatted(cpf, UUID.randomUUID(), PASSWORD)))
                .andReturn().getResponse().getContentAsString();
        UUID customerId = UUID.fromString(JsonPath.read(body, "$.customerId"));
        approveKyc.execute(new ApproveKycInput(customerId));
        UUID accountId = createAccount.execute(new CreateAccount.Input(customerId, "CHECKING")).accountId();
        if (new BigDecimal(balance).signum() > 0) depositAccount.execute(new DepositAccount.Input(accountId, new BigDecimal(balance)));
        String login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cpf\":\"%s\",\"password\":\"%s\"}".formatted(cpf, PASSWORD)))
                .andReturn().getResponse().getContentAsString();
        return new Customer(customerId, JsonPath.read(login, "$.accessToken"), accountId);
    }

    private UUID createBox(Customer c, String name, String goal) throws Exception {
        String body = goal == null ? "{\"name\":\"%s\"}".formatted(name) : "{\"name\":\"%s\",\"goalAmount\":%s}".formatted(name, goal);
        String response = mvc.perform(post("/api/accounts/" + c.accountId() + "/savings-boxes").header("Authorization", bearer(c))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(response, "$.id"));
    }

    private ResultActions move(Customer c, UUID boxId, String operation, String amount, String idempotencyKey) throws Exception {
        var request = post("/api/savings-boxes/" + boxId + "/" + operation).header("Authorization", bearer(c))
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":" + amount + "}");
        if (idempotencyKey != null) request.header("Idempotency-Key", idempotencyKey);
        return mvc.perform(request);
    }

    private BigDecimal accountBalance(Customer c) {
        return accountRepository.findById(c.accountId()).orElseThrow().getBalance().amount();
    }

    private BigDecimal boxBalance(UUID boxId) {
        return jdbcTemplate.queryForObject("SELECT balance FROM savings_boxes WHERE id = ?", BigDecimal.class, boxId);
    }

    private static String bearer(Customer c) {
        return "Bearer " + c.token();
    }

    @Test
    void guardarEResgatar_movemConta_eAparecemNosDoisExtratos() throws Exception {
        Customer maria = customerWith("1000.00");
        UUID box = createBox(maria, "Viagem", "5000.00");

        move(maria, box, "deposit", "300.00", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(300.00))
                .andExpect(jsonPath("$.goalProgressPercent").value(6));
        move(maria, box, "withdraw", "100.00", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.box.balance").value(200.00))
                .andExpect(jsonPath("$.withdrawal.net").value(100.00)); // guardou e tirou no mesmo dia: sem imposto

        assertThat(accountBalance(maria)).isEqualByComparingTo("800.00");

        mvc.perform(get("/api/savings-boxes/" + box).header("Authorization", bearer(maria)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.box.name").value("Viagem"))
                .andExpect(jsonPath("$.movements.length()").value(2))
                .andExpect(jsonPath("$.movements[0].type").value("WITHDRAWAL"))
                .andExpect(jsonPath("$.movements[1].type").value("DEPOSIT"));

        List<String> entryTypes = jdbcTemplate.queryForList(
                "SELECT type FROM account_entries WHERE account_id = ? ORDER BY created_at", String.class, maria.accountId());
        assertThat(entryTypes).containsSubsequence("DEPOSIT", "SAVINGS_DEPOSIT", "SAVINGS_WITHDRAWAL");

        mvc.perform(get("/api/accounts/" + maria.accountId() + "/savings-boxes").header("Authorization", bearer(maria)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void guardarMaisQueOSaldoDaConta_naoMexeEmNada() throws Exception {
        Customer joao = customerWith("50.00");
        UUID box = createBox(joao, "Reserva", null);

        move(joao, box, "deposit", "50.01", null).andExpect(status().isUnprocessableEntity());

        assertThat(accountBalance(joao)).isEqualByComparingTo("50.00");
        assertThat(boxBalance(box)).isEqualByComparingTo("0");
        Integer movements = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM savings_box_movements WHERE box_id = ?", Integer.class, box);
        assertThat(movements).isZero();
    }

    @Test
    void resgatarMaisQueOSaldoDaCaixinha_eRecusado() throws Exception {
        Customer ana = customerWith("100.00");
        UUID box = createBox(ana, "Reserva", null);
        move(ana, box, "deposit", "40.00", null).andExpect(status().isOk());

        move(ana, box, "withdraw", "40.01", null).andExpect(status().isUnprocessableEntity());
        assertThat(accountBalance(ana)).isEqualByComparingTo("60.00");
    }

    @Test
    void rendimento_creditaDiasUteis_eNaoRendeDuasVezes() throws Exception {
        Customer pedro = customerWith("10000.00");
        UUID box = createBox(pedro, "Rende", null);
        move(pedro, box, "deposit", "10000.00", null).andExpect(status().isOk());

        LocalDate created = jdbcTemplate.queryForObject("SELECT last_yield_date FROM savings_boxes WHERE id = ?", LocalDate.class, box);
        LocalDate runDate = created.plusDays(8); // rende até created + 7
        int businessDays = BusinessCalendar.businessDaysBetween(created, runDate.minusDays(1));

        runSavingsYield.execute(runDate);
        BigDecimal afterFirstRun = boxBalance(box);

        // ~R$ 5,25 por dia útil sobre R$ 10.000 a 14,15% a.a.
        BigDecimal perDay = new BigDecimal("5.25");
        assertThat(afterFirstRun.subtract(new BigDecimal("10000.00")))
                .isBetween(perDay.multiply(BigDecimal.valueOf(businessDays)).subtract(new BigDecimal("0.10")),
                        perDay.multiply(BigDecimal.valueOf(businessDays)).add(new BigDecimal("0.10")));

        runSavingsYield.execute(runDate); // reprocessar o mesmo dia
        assertThat(boxBalance(box)).isEqualByComparingTo(afterFirstRun);

        mvc.perform(get("/api/savings-boxes/" + box).header("Authorization", bearer(pedro)))
                .andExpect(jsonPath("$.box.yieldCalculatedUntil").value(runDate.minusDays(1).toString()))
                .andExpect(jsonPath("$.movements[0].type").value("YIELD"))
                .andExpect(jsonPath("$.box.totalYield").value(afterFirstRun.subtract(new BigDecimal("10000.00")).doubleValue()));
    }

    /** Volta a caixinha (e os lotes) {@code days} dias no tempo e rende até ontem: como se tivesse guardado lá atrás. */
    private void ageAndYield(UUID box, int days) {
        jdbcTemplate.update("UPDATE savings_box_lots SET deposited_on = deposited_on - ? WHERE box_id = ?", days, box);
        jdbcTemplate.update("UPDATE savings_boxes SET last_yield_date = last_yield_date - ? WHERE id = ?", days, box);
        runSavingsYield.execute(LocalDate.now());
    }

    @Test
    void encerrar_devolveOLiquidoParaConta() throws Exception {
        Customer lia = customerWith("1000.00");
        UUID box = createBox(lia, "Curta", null);
        move(lia, box, "deposit", "1000.00", null).andExpect(status().isOk());
        ageAndYield(box, 40); // 40 dias: já sem IOF, IR de 22,5%
        BigDecimal withYield = boxBalance(box);
        BigDecimal gain = withYield.subtract(new BigDecimal("1000.00"));
        assertThat(gain).isPositive();
        BigDecimal incomeTax = gain.multiply(new BigDecimal("0.225")).setScale(2, java.math.RoundingMode.DOWN);

        mvc.perform(delete("/api/savings-boxes/" + box).header("Authorization", bearer(lia)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.box.status").value("CLOSED"))
                .andExpect(jsonPath("$.box.balance").value(0))
                .andExpect(jsonPath("$.withdrawal.gross").value(withYield.doubleValue()))
                .andExpect(jsonPath("$.withdrawal.iof").value(0))
                .andExpect(jsonPath("$.withdrawal.incomeTax").value(incomeTax.doubleValue()));

        BigDecimal net = withYield.subtract(incomeTax);
        assertThat(accountBalance(lia)).isEqualByComparingTo(net);
        List<String> entries = jdbcTemplate.queryForList("SELECT amount::text FROM account_entries WHERE account_id = ? "
                + "AND type = 'SAVINGS_WITHDRAWAL'", String.class, lia.accountId());
        assertThat(entries).containsExactly(net.toPlainString()); // no extrato da conta, o líquido
        move(lia, box, "deposit", "1.00", null).andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/accounts/" + lia.accountId() + "/savings-boxes").header("Authorization", bearer(lia)))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void resgateNovo_pagaIof_eSimulacaoBateComOResgate() throws Exception {
        Customer rui = customerWith("2000.00");
        UUID box = createBox(rui, "Impostos", null);
        move(rui, box, "deposit", "2000.00", null).andExpect(status().isOk());
        ageAndYield(box, 10); // 10 dias: IOF de 66% do rendimento

        String preview = mvc.perform(get("/api/savings-boxes/" + box + "/withdrawal-preview?amount=500.00")
                        .header("Authorization", bearer(rui)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        BigDecimal iof = new BigDecimal(JsonPath.read(preview, "$.iof").toString());
        BigDecimal net = new BigDecimal(JsonPath.read(preview, "$.net").toString());
        assertThat(iof).isPositive();
        assertThat(net).isLessThan(new BigDecimal("500.00"));

        mvc.perform(get("/api/savings-boxes/" + box).header("Authorization", bearer(rui)))
                .andExpect(jsonPath("$.lots.length()").value(1))
                .andExpect(jsonPath("$.lots[0].daysHeld").value(10))
                .andExpect(jsonPath("$.lots[0].iofRate").value(0.66))
                .andExpect(jsonPath("$.lots[0].incomeTaxRate").value(0.225));

        BigDecimal accountBefore = accountBalance(rui);
        move(rui, box, "withdraw", "500.00", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.withdrawal.iof").value(iof.doubleValue()))
                .andExpect(jsonPath("$.withdrawal.net").value(net.doubleValue()));
        assertThat(accountBalance(rui)).isEqualByComparingTo(accountBefore.add(net));

        mvc.perform(get("/api/savings-boxes/" + box).header("Authorization", bearer(rui)))
                .andExpect(jsonPath("$.movements[0].type").value("WITHDRAWAL"))
                .andExpect(jsonPath("$.movements[0].amount").value(500.00))
                .andExpect(jsonPath("$.movements[0].iof").value(iof.doubleValue()))
                .andExpect(jsonPath("$.movements[0].netAmount").value(net.doubleValue()));
    }

    @Test
    void caixinhaDeOutroCliente_respondeNaoEncontrada() throws Exception {
        Customer dono = customerWith("100.00");
        Customer curioso = customerWith("100.00");
        UUID box = createBox(dono, "Segredo", null);

        mvc.perform(get("/api/savings-boxes/" + box).header("Authorization", bearer(curioso)))
                .andExpect(status().isNotFound());
        move(curioso, box, "withdraw", "1.00", null).andExpect(status().isNotFound());
        mvc.perform(post("/api/accounts/" + dono.accountId() + "/savings-boxes").header("Authorization", bearer(curioso))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Invasão\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void contaComCaixinhaAtiva_naoPodeSerEncerrada() throws Exception {
        Customer bia = customerWith("0");
        createBox(bia, "Vazia", null);

        mvc.perform(patch("/api/accounts/" + bia.accountId() + "/close").header("Authorization", bearer(bia)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("caixinhas")));
    }

    @Test
    void retryDoGuardar_comAMesmaChave_debitaUmaVez() throws Exception {
        Customer leo = customerWith("500.00");
        UUID box = createBox(leo, "Idem", null);
        String key = "guardar-" + UUID.randomUUID();

        move(leo, box, "deposit", "100.00", key).andExpect(status().isOk());
        move(leo, box, "deposit", "100.00", key).andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"));

        assertThat(accountBalance(leo)).isEqualByComparingTo("400.00");
        assertThat(boxBalance(box)).isEqualByComparingTo("100.00");
    }

    @Test
    void atingirAMeta_geraNotificacao() throws Exception {
        Customer gabi = customerWith("500.00");
        UUID box = createBox(gabi, "Celular", "200.00");

        move(gabi, box, "deposit", "200.00", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.goalProgressPercent").value(100))
                .andExpect(jsonPath("$.goalReachedAt").isNotEmpty());

        awaitUntil(() -> listNotifications.execute(new ListNotifications.Input(gabi.id(), false)).notifications().stream()
                .anyMatch(n -> n.type().equals("SAVINGS_GOAL_REACHED")), Duration.ofSeconds(15));
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
