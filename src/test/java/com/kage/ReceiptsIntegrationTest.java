package com.kage;

import com.jayway.jsonpath.JsonPath;
import com.kage.account.application.usecase.CreateAccount;
import com.kage.account.application.usecase.DepositAccount;
import com.kage.customer.application.usecase.approvekyc.ApproveKycInput;
import com.kage.customer.application.usecase.approvekyc.ApproveKycUseCase;
import com.kage.payment.domain.enums.PixTransactionStatus;
import com.kage.payment.domain.repository.PixTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Comprovantes de PIX e de boleto pelo HTTP: o conteúdo, a máscara do CPF e quem pode ver. */
class ReceiptsIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Sombra-2026";
    private static final String MASKED_CPF = "\\*\\*\\*\\.\\d{3}\\.\\d{3}-\\*\\*";
    private static final String AUTH_CODE = "[0-9A-F]{4}(-[0-9A-F]{4}){7}";

    @Autowired WebApplicationContext context;
    @Autowired ApproveKycUseCase approveKyc;
    @Autowired CreateAccount createAccount;
    @Autowired DepositAccount depositAccount;
    @Autowired PixTransactionRepository pixTransactionRepository;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private record Customer(String token, UUID accountId, String key) {}

    private Customer customer(String name, String balance) throws Exception {
        String cpf = randomCpf();
        String body = mvc.perform(post("/api/auth/signup").header("X-Device-Id", TEST_DEVICE).contentType(MediaType.APPLICATION_JSON)
                        .content("""
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
        String token = JsonPath.read(login, "$.accessToken");
        String key = name.toLowerCase().replace(' ', '.') + "." + UUID.randomUUID().toString().substring(0, 8) + "@kage.com";
        mvc.perform(post("/api/pix/keys").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"%s\",\"keyType\":\"EMAIL\",\"keyValue\":\"%s\"}".formatted(accountId, key)))
                .andExpect(status().isCreated());
        return new Customer(token, accountId, key);
    }

    private ResultActions as(Customer c, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer " + c.token()).contentType(MediaType.APPLICATION_JSON));
    }

    @Test
    void comprovanteDePix_paraQuemPagouEQuemRecebeu_soDepoisDeConcluido() throws Exception {
        Customer maria = customer("Maria Comprovante", "500.00");
        Customer joao = customer("Joao Recebedor", "0");
        Customer curioso = customer("Curioso Comprovante", "0");

        String sent = as(maria, post("/api/pix/send").content("""
                {"sourceAccountId":"%s","targetPixKey":"%s","amount":120.00,"description":"almoço"}"""
                .formatted(maria.accountId(), joao.key()))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(sent, "$.transactionId"));
        awaitUntil(() -> pixTransactionRepository.findById(id).orElseThrow().getStatus() == PixTransactionStatus.COMPLETED,
                Duration.ofSeconds(15));

        String receipt = as(maria, get("/api/pix/transactions/" + id + "/receipt")).andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("PIX"))
                .andExpect(jsonPath("$.amount").value(120.00))
                .andExpect(jsonPath("$.description").value("almoço"))
                .andExpect(jsonPath("$.e2eId").value(matchesPattern("E2E[0-9A-F]{32}")))
                .andExpect(jsonPath("$.paidAt").exists())
                .andExpect(jsonPath("$.payer.name").value("Maria Comprovante"))
                .andExpect(jsonPath("$.payer.document").value(matchesPattern(MASKED_CPF)))
                .andExpect(jsonPath("$.payer.institution").value("Kage (999)"))
                .andExpect(jsonPath("$.payee.name").value("Joao Recebedor"))
                .andExpect(jsonPath("$.payee.pixKey").value(joao.key()))
                .andExpect(jsonPath("$.payee.account").exists())
                .andExpect(jsonPath("$.authenticationCode").value(matchesPattern(AUTH_CODE)))
                .andReturn().getResponse().getContentAsString();

        // quem recebeu vê o mesmo comprovante (mesmo código); quem não é parte, 404
        String forJoao = as(joao, get("/api/pix/transactions/" + id + "/receipt")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(forJoao, "$.authenticationCode")).isEqualTo(JsonPath.read(receipt, "$.authenticationCode"));
        as(curioso, get("/api/pix/transactions/" + id + "/receipt")).andExpect(status().isNotFound());

        // PIX agendado ainda não aconteceu: sem comprovante
        String scheduled = as(maria, post("/api/pix/scheduled").content("""
                {"sourceAccountId":"%s","targetPixKey":"%s","amount":10.00,"scheduledDate":"%s"}"""
                .formatted(maria.accountId(), joao.key(), LocalDate.now().plusDays(3))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        as(maria, get("/api/pix/transactions/" + JsonPath.read(scheduled, "$.transactionId") + "/receipt"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(matchesPattern("O comprovante sai quando o PIX é concluído.*")));
    }

    @Test
    void comprovanteDeBoleto_soParaQuemPagou() throws Exception {
        Customer escola = customer("Escola Boleto", "0");
        Customer pai = customer("Pai Pagador", "1000.00");

        String boleto = as(escola, post("/api/accounts/" + escola.accountId() + "/boletos").content("""
                {"amount":350.00,"dueDate":"%s","payerName":"Pai Pagador","payerDocument":"123.456.789-09",
                 "description":"Mensalidade escolar","finePercent":2,"monthlyInterestPercent":1}"""
                .formatted(LocalDate.now().plusDays(10)))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String line = JsonPath.read(boleto, "$.digitableLine");

        String paid = as(pai, post("/api/accounts/" + pai.accountId() + "/boleto-payments").content("{\"code\":\"%s\"}".formatted(line)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String paymentId = JsonPath.read(paid, "$.paymentId");

        as(pai, get("/api/boleto-payments/" + paymentId + "/receipt")).andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("BOLETO"))
                .andExpect(jsonPath("$.digitableLine").value(line))
                .andExpect(jsonPath("$.beneficiary.name").value("Escola Boleto"))
                .andExpect(jsonPath("$.beneficiary.document").value(matchesPattern(MASKED_CPF)))
                .andExpect(jsonPath("$.beneficiary.bankCode").value("999"))
                .andExpect(jsonPath("$.payer.name").value("Pai Pagador"))
                .andExpect(jsonPath("$.faceAmount").value(350.00))
                .andExpect(jsonPath("$.fine").value(0))
                .andExpect(jsonPath("$.paidAmount").value(350.00))
                .andExpect(jsonPath("$.authenticationCode").value(matchesPattern(AUTH_CODE)));

        // quem recebeu acompanha pelo boleto, não pelo comprovante de quem pagou
        as(escola, get("/api/boleto-payments/" + paymentId + "/receipt")).andExpect(status().isNotFound());
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
