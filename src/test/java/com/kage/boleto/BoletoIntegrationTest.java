package com.kage.boleto;

import com.jayway.jsonpath.JsonPath;
import com.kage.AbstractIntegrationTest;
import com.kage.account.application.usecase.CreateAccount;
import com.kage.account.application.usecase.DepositAccount;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.boleto.application.usecase.PayBoletos;
import com.kage.boleto.domain.valueobject.BoletoCode;
import com.kage.customer.application.usecase.approvekyc.ApproveKycInput;
import com.kage.customer.application.usecase.approvekyc.ApproveKycUseCase;
import com.kage.notification.application.usecase.ListNotifications;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Boletos pelo HTTP: emitir, consultar o código, pagar (do Kage e de fora) e nunca pagar duas vezes. */
class BoletoIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Sombra-2026";

    @Autowired WebApplicationContext context;
    @Autowired ApproveKycUseCase approveKyc;
    @Autowired CreateAccount createAccount;
    @Autowired DepositAccount depositAccount;
    @Autowired AccountRepository accountRepository;
    @Autowired PayBoletos payBoletos;
    @Autowired ListNotifications listNotifications;
    @Autowired JdbcTemplate jdbcTemplate;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private record Customer(UUID id, String token, UUID accountId) {}

    private Customer customer(String name, String balance) throws Exception {
        String cpf = randomCpf();
        String body = mvc.perform(post("/api/auth/signup").header("X-Device-Id", TEST_DEVICE).contentType(MediaType.APPLICATION_JSON).content("""
                        {"fullName":"%s","cpf":"%s","email":"%s@teste.com","phone":"41999991234",
                         "birthDate":"1990-01-01","street":"Rua A","number":"1","city":"Curitiba","state":"PR",
                         "zipCode":"80000-000","password":"%s"}""".formatted(name, cpf, UUID.randomUUID(), PASSWORD)))
                .andReturn().getResponse().getContentAsString();
        UUID customerId = UUID.fromString(JsonPath.read(body, "$.customerId"));
        approveKyc.execute(new ApproveKycInput(customerId));
        UUID accountId = createAccount.execute(new CreateAccount.Input(customerId, "CHECKING")).accountId();
        if (new BigDecimal(balance).signum() > 0) depositAccount.execute(new DepositAccount.Input(accountId, new BigDecimal(balance)));
        String login = mvc.perform(post("/api/auth/login").header("X-Device-Id", TEST_DEVICE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cpf\":\"%s\",\"password\":\"%s\"}".formatted(cpf, PASSWORD)))
                .andReturn().getResponse().getContentAsString();
        return new Customer(customerId, JsonPath.read(login, "$.accessToken"), accountId);
    }

    private String bearer(Customer c) {
        return "Bearer " + c.token();
    }

    /** Emite e devolve o JSON do boleto. */
    private String issue(Customer beneficiary, String amount, LocalDate due) throws Exception {
        return mvc.perform(post("/api/accounts/" + beneficiary.accountId() + "/boletos").header("Authorization", bearer(beneficiary))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"amount":%s,"dueDate":"%s","payerName":"João Pagador","payerDocument":"123.456.789-09",
                                 "description":"Aluguel de dezembro","finePercent":2,"monthlyInterestPercent":1}"""
                                .formatted(amount, due)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    private ResultActions pay(Customer payer, String code, String amount) throws Exception {
        String body = amount == null ? "{\"code\":\"%s\"}".formatted(code) : "{\"code\":\"%s\",\"amount\":%s}".formatted(code, amount);
        return mvc.perform(post("/api/accounts/" + payer.accountId() + "/boleto-payments").header("Authorization", bearer(payer))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private BigDecimal balance(Customer c) {
        return accountRepository.findById(c.accountId()).orElseThrow().getBalance().amount();
    }

    private boolean notified(Customer c, String type) {
        return listNotifications.execute(new ListNotifications.Input(c.id(), false)).notifications().stream()
                .anyMatch(n -> n.type().equals(type));
    }

    @Test
    void boletoDoKage_pagoPorOutroCliente_liquidaNaHora_eNaoPagaDuasVezes() throws Exception {
        Customer maria = customer("Maria Beneficiária", "0");
        Customer joao = customer("João Pagador", "500.00");
        String boleto = issue(maria, "150.00", LocalDate.now().plusDays(10));
        String line = JsonPath.read(boleto, "$.digitableLine");
        String boletoId = JsonPath.read(boleto, "$.id");

        mvc.perform(get("/api/boletos/lookup").param("code", line).header("Authorization", bearer(joao)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("INTERNAL"))
                .andExpect(jsonPath("$.beneficiaryName").value("Maria Beneficiária"))
                .andExpect(jsonPath("$.beneficiaryDocument").value(org.hamcrest.Matchers.startsWith("***.")))
                .andExpect(jsonPath("$.amount").value(150.00))
                .andExpect(jsonPath("$.total").value(150.00))
                .andExpect(jsonPath("$.payable").value(true));

        pay(joao, line, null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("INTERNAL"))
                .andExpect(jsonPath("$.paidAmount").value(150.00));

        assertThat(balance(joao)).isEqualByComparingTo("350.00");
        assertThat(balance(maria)).isEqualByComparingTo("150.00");
        assertThat(jdbcTemplate.queryForList("SELECT type FROM account_entries WHERE account_id = ?", String.class, maria.accountId()))
                .contains("BOLETO_RECEIVED");
        assertThat(jdbcTemplate.queryForList("SELECT type FROM account_entries WHERE account_id = ?", String.class, joao.accountId()))
                .contains("BOLETO_PAYMENT");

        mvc.perform(get("/api/boletos/" + boletoId).header("Authorization", bearer(maria)))
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.paidVia").value("KAGE"));
        mvc.perform(get("/api/boletos/lookup").param("code", line).header("Authorization", bearer(joao)))
                .andExpect(jsonPath("$.payable").value(false))
                .andExpect(jsonPath("$.notPayableReason").value("Boleto já foi pago"));
        pay(joao, line, null).andExpect(status().isUnprocessableEntity());
        assertThat(balance(joao)).isEqualByComparingTo("350.00");

        awaitUntil(() -> notified(maria, "BOLETO_RECEIVED") && notified(joao, "BOLETO_PAYMENT_MADE"), Duration.ofSeconds(15));
    }

    @Test
    void boletoVencido_cobraMultaEJurosProRata() throws Exception {
        Customer ana = customer("Ana Beneficiária", "0");
        Customer rui = customer("Rui Pagador", "2000.00");
        String boleto = issue(ana, "1000.00", LocalDate.now().plusDays(1));
        String id = JsonPath.read(boleto, "$.id");
        // vencido há 15 dias, em uma quarta-feira útil escolhida para não cair na regra do dia útil seguinte
        LocalDate due = LocalDate.now().minusDays(15);
        while (!com.kage.shared.domain.service.BusinessCalendar.isBusinessDay(due)) due = due.minusDays(1);
        long days = java.time.temporal.ChronoUnit.DAYS.between(due, LocalDate.now());
        jdbcTemplate.update("UPDATE boletos SET due_date = ?, payable_until = ? WHERE id = ?::uuid",
                java.sql.Date.valueOf(due), java.sql.Date.valueOf(due.plusDays(59)), id);
        BigDecimal interest = new BigDecimal("10.00").multiply(BigDecimal.valueOf(days))
                .divide(BigDecimal.valueOf(30), 2, java.math.RoundingMode.HALF_UP);
        BigDecimal total = new BigDecimal("1020.00").add(interest);

        mvc.perform(get("/api/boletos/lookup").param("code", (String) JsonPath.read(boleto, "$.barcode"))
                        .header("Authorization", bearer(rui)))
                .andExpect(jsonPath("$.fine").value(20.00))
                .andExpect(jsonPath("$.interest").value(interest.doubleValue()))
                .andExpect(jsonPath("$.total").value(total.doubleValue()));

        pay(rui, JsonPath.read(boleto, "$.barcode"), null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.fine").value(20.00))
                .andExpect(jsonPath("$.paidAmount").value(total.doubleValue()));
        assertThat(balance(ana)).isEqualByComparingTo(total);
    }

    @Test
    void boletoDeOutroBanco_debitaNaHora_eNaoPagaDuasVezes() throws Exception {
        Customer leo = customer("Leo Pagador", "300.00");
        String free = "%025d".formatted(ThreadLocalRandom.current().nextLong(1_000_000_000_000L));
        BoletoCode external = BoletoCode.build("341", LocalDate.now().plusDays(5), new BigDecimal("89.90"), free);

        mvc.perform(get("/api/boletos/lookup").param("code", external.formattedDigitableLine()).header("Authorization", bearer(leo)))
                .andExpect(jsonPath("$.kind").value("EXTERNAL"))
                .andExpect(jsonPath("$.bankCode").value("341"))
                .andExpect(jsonPath("$.amount").value(89.90))
                .andExpect(jsonPath("$.dueDate").value(LocalDate.now().plusDays(5).toString()));

        pay(leo, external.digitableLine(), "50.00").andExpect(status().isBadRequest()); // valor impresso manda
        pay(leo, external.digitableLine(), null).andExpect(status().isCreated()).andExpect(jsonPath("$.kind").value("EXTERNAL"));
        pay(leo, external.barcode(), null).andExpect(status().isUnprocessableEntity());

        assertThat(balance(leo)).isEqualByComparingTo("210.10");
        mvc.perform(get("/api/accounts/" + leo.accountId() + "/boleto-payments").header("Authorization", bearer(leo)))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].paidAmount").value(89.90));
    }

    @Test
    void boletoDeFora_semValorImpresso_exigeOValor() throws Exception {
        Customer bia = customer("Bia Pagadora", "300.00");
        String free = "%025d".formatted(ThreadLocalRandom.current().nextLong(1_000_000_000_000L));
        BoletoCode noAmount = BoletoCode.build("237", LocalDate.now().plusDays(5), BigDecimal.ZERO, free);

        pay(bia, noAmount.barcode(), null).andExpect(status().isBadRequest());
        pay(bia, noAmount.barcode(), "120.00").andExpect(status().isCreated());
        assertThat(balance(bia)).isEqualByComparingTo("180.00");
    }

    @Test
    void semSaldo_naoPagaNemMarcaComoPago() throws Exception {
        Customer dono = customer("Dono", "0");
        Customer pobre = customer("Sem Saldo", "10.00");
        String boleto = issue(dono, "100.00", LocalDate.now().plusDays(3));

        pay(pobre, JsonPath.read(boleto, "$.barcode"), null).andExpect(status().isUnprocessableEntity());

        assertThat(balance(pobre)).isEqualByComparingTo("10.00");
        mvc.perform(get("/api/boletos/" + JsonPath.read(boleto, "$.id")).header("Authorization", bearer(dono)))
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void regras_proprioBoleto_cancelado_eDeOutroCliente() throws Exception {
        Customer dono = customer("Dono", "100.00");
        Customer outro = customer("Outro", "100.00");
        String boleto = issue(dono, "50.00", LocalDate.now().plusDays(3));
        String id = JsonPath.read(boleto, "$.id");

        pay(dono, JsonPath.read(boleto, "$.barcode"), null).andExpect(status().isUnprocessableEntity()); // o próprio
        mvc.perform(get("/api/boletos/" + id).header("Authorization", bearer(outro))).andExpect(status().isNotFound());
        mvc.perform(delete("/api/boletos/" + id).header("Authorization", bearer(outro))).andExpect(status().isNotFound());

        mvc.perform(delete("/api/boletos/" + id).header("Authorization", bearer(dono)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        pay(outro, JsonPath.read(boleto, "$.barcode"), null).andExpect(status().isUnprocessableEntity());

        String unregistered = BoletoCode.build("999", LocalDate.now().plusDays(3), new BigDecimal("10.00"),
                "%025d".formatted(ThreadLocalRandom.current().nextLong(1_000_000_000L))).barcode();
        mvc.perform(get("/api/boletos/lookup").param("code", unregistered).header("Authorization", bearer(outro)))
                .andExpect(status().isNotFound()); // código do Kage sem registro
        mvc.perform(get("/api/boletos/lookup").param("code", "1234").header("Authorization", bearer(outro)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void pagoEmOutroBanco_chegaPelaCompensacao() throws Exception {
        Customer lia = customer("Lia Beneficiária", "0");
        String boleto = issue(lia, "75.00", LocalDate.now().plusDays(2));

        payBoletos.settleFromClearing(UUID.fromString(JsonPath.read(boleto, "$.id")));

        assertThat(balance(lia)).isEqualByComparingTo("75.00");
        List<String> statuses = jdbcTemplate.queryForList("SELECT paid_via FROM boletos WHERE id = ?::uuid", String.class,
                (String) JsonPath.read(boleto, "$.id"));
        assertThat(statuses).containsExactly("COMPENSACAO");
        mvc.perform(get("/api/accounts/" + lia.accountId() + "/boletos").header("Authorization", bearer(lia)))
                .andExpect(jsonPath("$[0].status").value("PAID"));
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
