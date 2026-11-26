package com.kage.payment;

import com.jayway.jsonpath.JsonPath;
import com.kage.AbstractIntegrationTest;
import com.kage.account.application.usecase.CreateAccount;
import com.kage.account.application.usecase.DepositAccount;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.customer.application.usecase.approvekyc.ApproveKycInput;
import com.kage.customer.application.usecase.approvekyc.ApproveKycUseCase;
import com.kage.notification.application.usecase.ListNotifications;
import com.kage.payment.application.usecase.RunScheduledPix;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PIX Automático pelo HTTP, com clientes de verdade (login, papéis): a academia pede, o pagador
 * autoriza, a academia cobra, e a cobrança é paga no vencimento pelo job dos agendados.
 */
class PixAutomaticIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Sombra-2026";
    private static final LocalDate TODAY = LocalDate.now();
    private static final LocalDate DUE = TODAY.plusDays(5);

    @Autowired WebApplicationContext context;
    @Autowired ApproveKycUseCase approveKyc;
    @Autowired CreateAccount createAccount;
    @Autowired DepositAccount depositAccount;
    @Autowired AccountRepository accountRepository;
    @Autowired ListNotifications listNotifications;
    @Autowired RunScheduledPix runScheduledPix;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private record Customer(UUID id, String token, UUID accountId, String key) {}

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
        String token = JsonPath.read(login, "$.accessToken");
        String key = "chave-" + UUID.randomUUID();
        mvc.perform(post("/api/pix/keys").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"%s\",\"keyType\":\"RANDOM\",\"keyValue\":\"%s\"}".formatted(accountId, key)))
                .andExpect(status().isCreated());
        return new Customer(customerId, token, accountId, key);
    }

    private ResultActions as(Customer c, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer " + c.token()).contentType(MediaType.APPLICATION_JSON));
    }

    /** A academia pede; devolve o id da autorização. */
    private String requestAuthorization(Customer receiver, Customer payer, String fixedAmount) throws Exception {
        return requestAuthorization(receiver, payer, fixedAmount, "NONE");
    }

    private String requestAuthorization(Customer receiver, Customer payer, String fixedAmount, String retryPolicy)
            throws Exception {
        String body = as(receiver, post("/api/pix/automatic/authorizations").content("""
                {"receiverAccountId":"%s","receiverPixKey":"%s","payerPixKey":"%s","description":"Mensalidade",
                 "contractReference":"matrícula 42","frequency":"MONTHLY","fixedAmount":%s,"startDate":"%s",
                 "retryPolicy":"%s"}"""
                .formatted(receiver.accountId(), receiver.key(), payer.key(), fixedAmount, TODAY.plusDays(1), retryPolicy)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private ResultActions charge(Customer receiver, String authorizationId, String amount, LocalDate due) throws Exception {
        return as(receiver, post("/api/pix/automatic/authorizations/" + authorizationId + "/charges")
                .content("{\"amount\":%s,\"dueDate\":\"%s\"}".formatted(amount, due)));
    }

    private BigDecimal balance(Customer c) {
        return accountRepository.findById(c.accountId()).orElseThrow().getBalance().amount();
    }

    private boolean notified(Customer c, String type) {
        return listNotifications.execute(new ListNotifications.Input(c.id(), false)).notifications().stream()
                .anyMatch(n -> n.type().equals(type));
    }

    @Test
    void academiaPede_pagadorAutoriza_cobrancaPagaNoVencimento() throws Exception {
        Customer academia = customer("Academia Forte", "0");
        Customer joao = customer("João Pagador", "500.00");
        Customer curioso = customer("Curioso", "0");

        String id = requestAuthorization(academia, joao, "49.90");
        awaitUntil(() -> notified(joao, "PIX_AUTOMATIC_REQUESTED"), Duration.ofSeconds(15));

        // o pagador vê o pedido com o nome de quem recebe; quem não é parte não enxerga
        as(joao, get("/api/pix/automatic/authorizations/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.receiverName").value("Academia Forte"));
        as(curioso, get("/api/pix/automatic/authorizations/" + id)).andExpect(status().isNotFound());

        // só o pagador autoriza
        as(academia, post("/api/pix/automatic/authorizations/" + id + "/approve")).andExpect(status().isUnprocessableEntity());
        as(joao, post("/api/pix/automatic/authorizations/" + id + "/approve")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // só quem recebe cobra; uma cobrança por ciclo
        charge(joao, id, "49.90", DUE).andExpect(status().isUnprocessableEntity());
        charge(academia, id, "49.90", DUE).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.cycle").value(0));
        charge(academia, id, "49.90", DUE.plusDays(1)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Já existe cobrança para o ciclo deste vencimento"));
        awaitUntil(() -> notified(joao, "PIX_AUTOMATIC_CHARGE_SCHEDULED"), Duration.ofSeconds(15));

        runScheduledPix.execute(DUE.minusDays(1));
        assertThat(balance(joao)).isEqualByComparingTo("500.00");

        runScheduledPix.execute(DUE);
        awaitUntil(() -> balance(academia).compareTo(new BigDecimal("49.90")) == 0, Duration.ofSeconds(15));
        assertThat(balance(joao)).isEqualByComparingTo("450.10");
        awaitUntil(() -> {
            try {
                String body = as(academia, get("/api/pix/automatic/authorizations/" + id)).andReturn().getResponse().getContentAsString();
                return "PAID".equals(JsonPath.read(body, "$.charges[0].status"));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, Duration.ofSeconds(15));

        // a lista da conta mostra a autorização para os dois lados
        as(joao, get("/api/pix/automatic/authorizations?accountId=" + joao.accountId()))
                .andExpect(jsonPath("$[0].id").value(id));
        as(academia, get("/api/pix/automatic/authorizations?accountId=" + academia.accountId()))
                .andExpect(jsonPath("$[0].id").value(id));
    }

    @Test
    void valorVariavel_respeitaOTetoDoPagador() throws Exception {
        Customer escola = customer("Escola Aprender", "0");
        Customer ana = customer("Ana Pagadora", "500.00");
        String id = requestAuthorization(escola, ana, "null");

        as(ana, post("/api/pix/automatic/authorizations/" + id + "/approve").content("{\"maxAmount\":100.00}"))
                .andExpect(jsonPath("$.maxAmount").value(100.00));

        charge(escola, id, "150.00", DUE).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Valor acima do máximo autorizado pelo pagador (100.00)"));
        charge(escola, id, "80.00", DUE).andExpect(status().isCreated());
    }

    @Test
    void pagadorCancelaUmaCobranca_eDepoisAAutorizacao() throws Exception {
        Customer streaming = customer("Streaming Mais", "0");
        Customer bia = customer("Bia Pagadora", "500.00");
        String id = requestAuthorization(streaming, bia, "29.90");
        as(bia, post("/api/pix/automatic/authorizations/" + id + "/approve")).andExpect(status().isOk());

        String charge = charge(streaming, id, "29.90", DUE).andReturn().getResponse().getContentAsString();
        String chargeId = JsonPath.read(charge, "$.id");

        // quem recebe não cancela a cobrança pelo pagador
        as(streaming, delete("/api/pix/automatic/charges/" + chargeId)).andExpect(status().isUnprocessableEntity());
        as(bia, delete("/api/pix/automatic/charges/" + chargeId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        runScheduledPix.execute(DUE);
        sleep(Duration.ofSeconds(1));
        assertThat(balance(bia)).isEqualByComparingTo("500.00");

        as(bia, delete("/api/pix/automatic/authorizations/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledBy").value("PAYER"));
        charge(streaming, id, "29.90", DUE.plusMonths(1).minusDays(5)).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void recebedorCancela_eAsCobrancasAgendadasCaem() throws Exception {
        Customer clube = customer("Clube Bom", "0");
        Customer caio = customer("Caio Pagador", "500.00");
        String id = requestAuthorization(clube, caio, "99.00");
        as(caio, post("/api/pix/automatic/authorizations/" + id + "/approve")).andExpect(status().isOk());
        charge(clube, id, "99.00", DUE).andExpect(status().isCreated());

        as(clube, delete("/api/pix/automatic/authorizations/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelledBy").value("RECEIVER"))
                .andExpect(jsonPath("$.charges[0].status").value("CANCELLED"));

        runScheduledPix.execute(DUE);
        sleep(Duration.ofSeconds(1));
        assertThat(balance(caio)).isEqualByComparingTo("500.00");
    }

    private ResultActions retry(Customer who, String chargeId, LocalDate date) throws Exception {
        return as(who, post("/api/pix/automatic/charges/" + chargeId + "/retry").content("{\"date\":\"%s\"}".formatted(date)));
    }

    private String chargeStatus(Customer who, String authorizationId) {
        try {
            String body = as(who, get("/api/pix/automatic/authorizations/" + authorizationId)).andReturn().getResponse()
                    .getContentAsString();
            return JsonPath.read(body, "$.charges[0].status");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Autorização aceita, com a cobrança de 49,90 do 1º ciclo já recusada por falta de saldo. */
    private String failedCharge(Customer receiver, Customer payer, String retryPolicy, String[] authorizationId) throws Exception {
        String id = requestAuthorization(receiver, payer, "49.90", retryPolicy);
        authorizationId[0] = id;
        as(payer, post("/api/pix/automatic/authorizations/" + id + "/approve")).andExpect(status().isOk());
        String chargeId = JsonPath.read(charge(receiver, id, "49.90", DUE).andReturn().getResponse().getContentAsString(), "$.id");
        runScheduledPix.execute(DUE);
        assertThat(chargeStatus(payer, id)).isEqualTo("FAILED");
        return chargeId;
    }

    @Test
    void semSaldoNoVencimento_quemRecebeTentaDeNovo_ePagaNoDiaSeguinte() throws Exception {
        Customer academia = customer("Academia Retry", "0");
        Customer lia = customer("Lia Pagadora", "0");
        String[] id = new String[1];
        String chargeId = failedCharge(academia, lia, "THREE_IN_SEVEN_DAYS", id);

        retry(lia, chargeId, DUE.plusDays(1)).andExpect(status().isUnprocessableEntity()); // só quem recebe
        retry(academia, chargeId, DUE).andExpect(status().isUnprocessableEntity())          // não no mesmo dia
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Uma tentativa por dia")));
        retry(academia, chargeId, DUE.plusDays(1)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.attempts.length()").value(2))
                .andExpect(jsonPath("$.attempts[0].status").value("FAILED"))
                .andExpect(jsonPath("$.attempts[0].failureReason").value(org.hamcrest.Matchers.containsString("Saldo")));
        retry(academia, chargeId, DUE.plusDays(2)).andExpect(status().isUnprocessableEntity()); // a anterior ainda não falhou

        awaitUntil(() -> listNotifications.execute(new ListNotifications.Input(lia.id(), false)).notifications().stream()
                .anyMatch(n -> n.title().equals("Nova tentativa do PIX Automático")), Duration.ofSeconds(15));

        depositAccount.execute(new DepositAccount.Input(lia.accountId(), new BigDecimal("100.00")));
        runScheduledPix.execute(DUE.plusDays(1));
        awaitUntil(() -> balance(academia).compareTo(new BigDecimal("49.90")) == 0, Duration.ofSeconds(15));
        awaitUntil(() -> "PAID".equals(chargeStatus(academia, id[0])), Duration.ofSeconds(15));

        retry(academia, chargeId, DUE.plusDays(3)).andExpect(status().isUnprocessableEntity()); // já foi paga
    }

    @Test
    void autorizacaoSemNovasTentativas_naoDeixaTentarDeNovo() throws Exception {
        Customer escola = customer("Escola Sem Retry", "0");
        Customer rui = customer("Rui Pagador", "0");
        String chargeId = failedCharge(escola, rui, "NONE", new String[1]);

        retry(escola, chargeId, DUE.plusDays(1)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Esta autorização não permite novas tentativas"));
    }

    @Test
    void noMaximoTresNovasTentativas() throws Exception {
        Customer clube = customer("Clube Retry", "0");
        Customer ivo = customer("Ivo Pagador", "0");
        String[] id = new String[1];
        String chargeId = failedCharge(clube, ivo, "THREE_IN_SEVEN_DAYS", id);

        for (int day = 1; day <= 3; day++) {
            retry(clube, chargeId, DUE.plusDays(day)).andExpect(status().isCreated());
            runScheduledPix.execute(DUE.plusDays(day)); // sem saldo de novo
            assertThat(chargeStatus(ivo, id[0])).isEqualTo("FAILED");
        }
        retry(clube, chargeId, DUE.plusDays(4)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("As 3 novas tentativas permitidas já foram feitas"));
    }

    @Test
    void pagadorRecusa_eNaoDaParaAutorizarDepois() throws Exception {
        Customer loja = customer("Loja Clube", "0");
        Customer davi = customer("Davi Pagador", "0");
        String id = requestAuthorization(loja, davi, "10.00");

        as(davi, post("/api/pix/automatic/authorizations/" + id + "/reject")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
        as(davi, post("/api/pix/automatic/authorizations/" + id + "/approve")).andExpect(status().isUnprocessableEntity());
        charge(loja, id, "10.00", DUE).andExpect(status().isUnprocessableEntity());
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
