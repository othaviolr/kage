package com.kage.shared;

import com.jayway.jsonpath.JsonPath;
import com.kage.AbstractIntegrationTest;
import com.kage.account.application.usecase.DepositAccount;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.customer.application.usecase.approvekyc.ApproveKycInput;
import com.kage.customer.application.usecase.approvekyc.ApproveKycUseCase;
import com.kage.account.application.usecase.CreateAccount;
import com.kage.payment.application.usecase.RegisterPixKey;
import com.kage.payment.domain.enums.PixKeyType;
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
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Idempotency-Key pelo HTTP de verdade: o retry de rede de um PIX/saque não move dinheiro duas vezes.
 */
class IdempotencyIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Sombra-2026";

    @Autowired WebApplicationContext context;
    @Autowired ApproveKycUseCase approveKyc;
    @Autowired CreateAccount createAccount;
    @Autowired DepositAccount depositAccount;
    @Autowired RegisterPixKey registerPixKey;
    @Autowired AccountRepository accountRepository;
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
                        {"fullName":"Cliente Idem","cpf":"%s","email":"%s@teste.com","phone":"41999991234",
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

    private ResultActions withdraw(Customer c, String amount, String key) throws Exception {
        var request = post("/api/accounts/" + c.accountId() + "/withdraw").header("Authorization", "Bearer " + c.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":" + amount + "}");
        if (key != null) request.header("Idempotency-Key", key);
        return mvc.perform(request);
    }

    private BigDecimal balance(Customer c) {
        return accountRepository.findById(c.accountId()).orElseThrow().getBalance().amount();
    }

    @Test
    void retryDoPix_comAMesmaChave_naoCriaOutraTransacao() throws Exception {
        Customer payer = customerWith("500.00");
        Customer receiver = customerWith("0");
        String pixKey = "chave-" + UUID.randomUUID();
        registerPixKey.execute(new RegisterPixKey.Input(receiver.accountId(), PixKeyType.RANDOM, pixKey));
        String body = "{\"sourceAccountId\":\"%s\",\"targetPixKey\":\"%s\",\"amount\":100.00}".formatted(payer.accountId(), pixKey);
        String key = UUID.randomUUID().toString();

        String first = mvc.perform(post("/api/pix/send").header("Authorization", "Bearer " + payer.token())
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andReturn().getResponse().getContentAsString();

        String retry = mvc.perform(post("/api/pix/send").header("Authorization", "Bearer " + payer.token())
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse().getContentAsString();

        String txId = JsonPath.read(first, "$.transactionId");
        assertThat((String) JsonPath.read(retry, "$.transactionId")).isEqualTo(txId);
        Integer transactions = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pix_transactions WHERE source_account_id = ?", Integer.class, payer.accountId());
        assertThat(transactions).isEqualTo(1);
    }

    @Test
    void retryDoSaque_debitaUmaVezSo() throws Exception {
        Customer maria = customerWith("500.00");
        String key = "saque-" + UUID.randomUUID();

        withdraw(maria, "100.00", key).andExpect(status().isOk()).andExpect(jsonPath("$.balance").value(400.00));
        withdraw(maria, "100.00", key).andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.balance").value(400.00));

        assertThat(balance(maria)).isEqualByComparingTo("400.00");
    }

    @Test
    void semChave_cadaRequisicaoExecuta() throws Exception {
        Customer maria = customerWith("500.00");

        withdraw(maria, "100.00", null).andExpect(status().isOk());
        withdraw(maria, "100.00", null).andExpect(status().isOk());

        assertThat(balance(maria)).isEqualByComparingTo("300.00");
    }

    @Test
    void mesmaChave_comOutroCorpo_deveResponder422_semExecutar() throws Exception {
        Customer maria = customerWith("500.00");
        String key = UUID.randomUUID().toString();
        withdraw(maria, "100.00", key).andExpect(status().isOk());

        withdraw(maria, "250.00", key)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("já usada em outra requisição")));

        assertThat(balance(maria)).isEqualByComparingTo("400.00");
    }

    @Test
    void erroDeNegocio_tambemERepetidoIgual() throws Exception {
        Customer maria = customerWith("50.00");
        String key = UUID.randomUUID().toString();

        withdraw(maria, "100.00", key).andExpect(status().isUnprocessableEntity());
        depositAccount.execute(new DepositAccount.Input(maria.accountId(), new BigDecimal("500.00")));

        // a resposta guardada vale: mesmo com saldo agora, a MESMA operação não roda de novo
        withdraw(maria, "100.00", key).andExpect(status().isUnprocessableEntity())
                .andExpect(header().string("Idempotent-Replayed", "true"));
        assertThat(balance(maria)).isEqualByComparingTo("550.00");
    }

    @Test
    void chavesSaoPorUsuario_aMesmaChaveDeOutroClienteNaoColide() throws Exception {
        Customer maria = customerWith("500.00");
        Customer joao = customerWith("500.00");
        String shared = "pedido-1";

        withdraw(maria, "100.00", shared).andExpect(status().isOk());
        withdraw(joao, "100.00", shared).andExpect(status().isOk()).andExpect(header().doesNotExist("Idempotent-Replayed"));

        assertThat(balance(maria)).isEqualByComparingTo("400.00");
        assertThat(balance(joao)).isEqualByComparingTo("400.00");
    }

    @Test
    void chaveEmAndamento_deveResponder409() throws Exception {
        Customer maria = customerWith("500.00");
        String key = UUID.randomUUID().toString();
        // simula a primeira requisição ainda rodando (ou a app que caiu no meio dela)
        jdbcTemplate.update("""
                INSERT INTO idempotency_keys (owner, idempotency_key, request_method, request_path, request_hash, status, created_at, expires_at)
                VALUES (?, ?, 'POST', ?, ?, 'IN_PROGRESS', now(), now() + interval '1 day')
                """, "CUSTOMER:" + maria.id(), key, "/api/accounts/" + maria.accountId() + "/withdraw",
                sha256("{\"amount\":100.00}"));

        withdraw(maria, "100.00", key).andExpect(status().isConflict());
        assertThat(balance(maria)).isEqualByComparingTo("500.00");
    }

    @Test
    void chaveInvalida_deveResponder400() throws Exception {
        Customer maria = customerWith("500.00");

        withdraw(maria, "100.00", "tem espaço e é longa demais".repeat(5)).andExpect(status().isBadRequest());
        assertThat(balance(maria)).isEqualByComparingTo("500.00");
    }

    private static String sha256(String body) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
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
        int r = 11 - (sum % 11);
        return r >= 10 ? 0 : r;
    }
}
