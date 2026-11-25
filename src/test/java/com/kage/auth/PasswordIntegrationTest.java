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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Troca e recuperação de senha pelo HTTP de verdade (com o token de recuperação exposto só no teste). */
class PasswordIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Sombra-2026";
    private static final String NEW_PASSWORD = "Nova-senha-2027";

    @Autowired WebApplicationContext context;
    @Autowired ListNotifications listNotifications;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private record Customer(UUID id, String cpf) {}

    private Customer signUp() throws Exception {
        String cpf = randomCpf();
        String body = mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content("""
                        {"fullName":"Cliente Senha","cpf":"%s","email":"%s@teste.com","phone":"41999991234",
                         "birthDate":"1990-01-01","street":"Rua A","number":"1","city":"Curitiba","state":"PR",
                         "zipCode":"80000-000","password":"%s"}""".formatted(cpf, UUID.randomUUID(), PASSWORD)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new Customer(UUID.fromString(JsonPath.read(body, "$.customerId")), cpf);
    }

    private ResultActions login(String cpf, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"cpf\":\"%s\",\"password\":\"%s\"}".formatted(cpf, password)));
    }

    private String token(String cpf, String password) throws Exception {
        return JsonPath.read(login(cpf, password).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
                "$.accessToken");
    }

    private ResultActions me(String token) throws Exception {
        return mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token));
    }

    private ResultActions forgot(String cpf) throws Exception {
        return mvc.perform(post("/api/auth/password/forgot").contentType(MediaType.APPLICATION_JSON)
                .content("{\"cpf\":\"%s\"}".formatted(cpf)));
    }

    private ResultActions reset(String token, String password) throws Exception {
        return mvc.perform(post("/api/auth/password/reset").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"%s\",\"newPassword\":\"%s\"}".formatted(token, password)));
    }

    @Test
    void trocarSenha_deveManterEstaSessao_derrubarAsOutras_eAvisar() throws Exception {
        Customer maria = signUp();
        String here = token(maria.cpf(), PASSWORD);
        String otherDevice = token(maria.cpf(), PASSWORD);

        mvc.perform(post("/api/auth/password").header("Authorization", "Bearer " + here).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"errada-1\",\"newPassword\":\"%s\"}".formatted(NEW_PASSWORD)))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/auth/password").header("Authorization", "Bearer " + here).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(PASSWORD, NEW_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.otherSessionsRevoked").value(1));

        me(here).andExpect(status().isOk());
        me(otherDevice).andExpect(status().isUnauthorized());
        login(maria.cpf(), PASSWORD).andExpect(status().isUnauthorized());
        login(maria.cpf(), NEW_PASSWORD).andExpect(status().isOk());

        awaitUntil(() -> listNotifications.execute(new ListNotifications.Input(maria.id(), false)).notifications().stream()
                .anyMatch(n -> n.type().equals("PASSWORD_CHANGED")), Duration.ofSeconds(15));
    }

    @Test
    void esqueciASenha_deveResponderIgual_existaOCpfOuNao() throws Exception {
        Customer maria = signUp();

        String known = forgot(maria.cpf()).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String unknown = forgot(randomCpf()).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();

        // fora o token de desenvolvimento, as duas respostas são idênticas
        org.assertj.core.api.Assertions.assertThat((String) JsonPath.read(known, "$.message"))
                .isEqualTo(JsonPath.read(unknown, "$.message"));
        org.assertj.core.api.Assertions.assertThat((Object) JsonPath.read(unknown, "$.devResetToken")).isNull();
    }

    @Test
    void recuperacao_deveTrocarASenha_destravarOLogin_eDerrubarTodasAsSessoes() throws Exception {
        Customer maria = signUp();
        String session = token(maria.cpf(), PASSWORD);
        for (int i = 0; i < 5; i++) login(maria.cpf(), "Errada-123").andExpect(status().isUnauthorized());
        login(maria.cpf(), PASSWORD).andExpect(status().isTooManyRequests()); // travado

        String resetToken = JsonPath.read(forgot(maria.cpf()).andReturn().getResponse().getContentAsString(), "$.devResetToken");

        reset(resetToken, "123").andExpect(status().isBadRequest());            // senha fraca não queima o link
        reset(resetToken, NEW_PASSWORD).andExpect(status().isNoContent());

        me(session).andExpect(status().isUnauthorized());                        // todas as sessões caíram
        login(maria.cpf(), NEW_PASSWORD).andExpect(status().isOk());             // e a trava foi liberada
        reset(resetToken, "Outra-senha-2028").andExpect(status().isBadRequest()) // link de uso único
                .andExpect(jsonPath("$.message").value("Link de recuperação inválido ou expirado. Peça um novo"));
    }

    @Test
    void pedirDeNovo_deveInvalidarOLinkAnterior() throws Exception {
        Customer maria = signUp();
        String first = JsonPath.read(forgot(maria.cpf()).andReturn().getResponse().getContentAsString(), "$.devResetToken");
        String second = JsonPath.read(forgot(maria.cpf()).andReturn().getResponse().getContentAsString(), "$.devResetToken");

        reset(first, NEW_PASSWORD).andExpect(status().isBadRequest());
        reset(second, NEW_PASSWORD).andExpect(status().isNoContent());
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
