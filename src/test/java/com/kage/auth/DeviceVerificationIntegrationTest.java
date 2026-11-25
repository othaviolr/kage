package com.kage.auth;

import com.jayway.jsonpath.JsonPath;
import com.kage.AbstractIntegrationTest;
import com.kage.notification.application.usecase.ListNotifications;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Login em dispositivo novo pelo HTTP: senha certa num aparelho desconhecido não abre sessão; o
 * código por SMS (aqui devolvido na resposta, só nos testes) abre e torna o aparelho confiável.
 */
class DeviceVerificationIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Sombra-2026";
    private static final String PHONE = "celular-da-maria-00001";
    private static final String NOTEBOOK = "notebook-da-maria-0001";

    @Autowired WebApplicationContext context;
    @Autowired ListNotifications listNotifications;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private record Customer(UUID id, String cpf) {}

    /** Cadastro pelo celular: ele já nasce confiável. */
    private Customer signUp() throws Exception {
        String cpf = randomCpf();
        String body = mvc.perform(post("/api/auth/signup").header("X-Device-Id", PHONE).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"Maria Dois Fatores","cpf":"%s","email":"%s@teste.com","phone":"41999991234",
                                 "birthDate":"1990-01-01","street":"Rua A","number":"1","city":"Curitiba","state":"PR",
                                 "zipCode":"80000-000","password":"%s"}""".formatted(cpf, UUID.randomUUID(), PASSWORD)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new Customer(UUID.fromString(JsonPath.read(body, "$.customerId")), cpf);
    }

    private ResultActions login(Customer c, String device) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .header("User-Agent", "Kage/teste").content("{\"cpf\":\"%s\",\"password\":\"%s\"}".formatted(c.cpf(), PASSWORD));
        if (device != null) request.header("X-Device-Id", device);
        return mvc.perform(request);
    }

    private record Challenge(String id, String code) {}

    private Challenge challenge(Customer c, String device) throws Exception {
        String body = login(c, device).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.verificationRequired").value(true))
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        return new Challenge(JsonPath.read(body, "$.challengeId"), JsonPath.read(body, "$.devCode"));
    }

    private ResultActions verify(Challenge challenge, String code, String device) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/auth/login/verify").contentType(MediaType.APPLICATION_JSON)
                .content("{\"challengeId\":\"%s\",\"code\":\"%s\"}".formatted(challenge.id(), code));
        if (device != null) request.header("X-Device-Id", device);
        return mvc.perform(request);
    }

    private String wrong(String code) {
        return code.equals("000000") ? "111111" : "000000";
    }

    @Test
    void aparelhoNovo_pedeCodigo_eDepoisEntraDireto() throws Exception {
        Customer maria = signUp();
        login(maria, PHONE).andExpect(status().isOk()).andExpect(jsonPath("$.accessToken").exists()); // aparelho do cadastro

        Challenge challenge = challenge(maria, NOTEBOOK);

        verify(challenge, wrong(challenge.code()), NOTEBOOK).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Código incorreto. Restam 4 tentativa(s)"));
        verify(challenge, challenge.code(), PHONE).andExpect(status().isUnauthorized()); // código de outro aparelho
        String tokens = verify(challenge, challenge.code(), NOTEBOOK).andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshToken").exists())
                .andReturn().getResponse().getContentAsString();
        verify(challenge, challenge.code(), NOTEBOOK).andExpect(status().isUnauthorized()); // uso único

        awaitUntil(() -> listNotifications.execute(new ListNotifications.Input(maria.id(), false)).notifications().stream()
                .anyMatch(n -> n.type().equals("NEW_DEVICE_LOGIN")), Duration.ofSeconds(15));

        login(maria, NOTEBOOK).andExpect(status().isOk()); // agora é confiável

        String token = JsonPath.read(tokens, "$.accessToken");
        mvc.perform(get("/api/auth/devices").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void cincoErros_queimamOCodigo() throws Exception {
        Customer maria = signUp();
        Challenge challenge = challenge(maria, NOTEBOOK);

        for (int i = 0; i < 4; i++) verify(challenge, wrong(challenge.code()), NOTEBOOK).andExpect(status().isUnauthorized());
        verify(challenge, wrong(challenge.code()), NOTEBOOK).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Código incorreto. Entre de novo para receber outro"));

        verify(challenge, challenge.code(), NOTEBOOK).andExpect(status().isUnauthorized()); // nem o certo vale mais
    }

    @Test
    void pedirOutroCodigo_invalidaOAnterior_eHaLimitePorHora() throws Exception {
        Customer maria = signUp();
        Challenge first = challenge(maria, NOTEBOOK);
        Challenge second = challenge(maria, NOTEBOOK);

        verify(first, first.code(), NOTEBOOK).andExpect(status().isUnauthorized());

        for (int i = 0; i < 3; i++) challenge(maria, NOTEBOOK); // 5 no total
        login(maria, NOTEBOOK).andExpect(status().isTooManyRequests());
        login(maria, PHONE).andExpect(status().isOk()); // o aparelho confiável segue entrando

        verify(second, second.code(), NOTEBOOK).andExpect(status().isUnauthorized()); // o 2º foi invalidado pelos seguintes
    }

    @Test
    void esquecerUmAparelho_fazOProximoLoginDelePedirCodigo() throws Exception {
        Customer maria = signUp();
        String token = JsonPath.read(login(maria, PHONE).andReturn().getResponse().getContentAsString(), "$.accessToken");

        String devices = mvc.perform(get("/api/auth/devices").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        String phoneId = JsonPath.read(devices, "$[0].id");

        mvc.perform(delete("/api/auth/devices/" + phoneId).header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/auth/devices/" + phoneId).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());

        challenge(maria, PHONE);
    }

    @Test
    void semIdentificadorDoAparelho_semprePedeCodigo_eNaoGuardaAparelho() throws Exception {
        Customer maria = signUp();
        Challenge challenge = challenge(maria, null);

        String token = JsonPath.read(verify(challenge, challenge.code(), null).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.accessToken");

        challenge(maria, null); // sem id, não há o que lembrar
        mvc.perform(get("/api/auth/devices").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.length()").value(1)); // só o do cadastro
    }

    @Test
    void senhaErrada_naoMandaCodigo_eIdentificadorInvalidoE400() throws Exception {
        Customer maria = signUp();
        mvc.perform(post("/api/auth/login").header("X-Device-Id", NOTEBOOK).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cpf\":\"%s\",\"password\":\"Errada-123\"}".formatted(maria.cpf())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.challengeId").doesNotExist());

        login(maria, "curto").andExpect(status().isBadRequest());
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
