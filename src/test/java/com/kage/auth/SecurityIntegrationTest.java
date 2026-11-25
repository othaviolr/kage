package com.kage.auth;

import com.jayway.jsonpath.JsonPath;
import com.kage.AbstractIntegrationTest;
import com.kage.account.application.usecase.CreateAccount;
import com.kage.customer.application.usecase.approvekyc.ApproveKycInput;
import com.kage.customer.application.usecase.approvekyc.ApproveKycUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A matriz de acesso pelo HTTP de verdade (filtro JWT + SecurityConfig + AccessGuard): quem não tem
 * token não entra, cliente só toca o que é dele (o resto responde 404), rota de backoffice é só do
 * ADMIN, e senha errada repetida trava o login.
 */
class SecurityIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Sombra-2026";

    @Autowired WebApplicationContext context;
    @Autowired ApproveKycUseCase approveKyc;
    @Autowired CreateAccount createAccount;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    // ---------- helpers ----------

    private record Customer(UUID id, String cpf, String token, UUID accountId) {}

    private Customer newCustomerWithAccount() throws Exception {
        String cpf = randomCpf();
        String body = mvc.perform(post("/api/auth/signup").header("X-Device-Id", TEST_DEVICE).contentType(MediaType.APPLICATION_JSON).content("""
                        {"fullName":"Cliente Seguro","cpf":"%s","email":"%s@teste.com","phone":"41999991234",
                         "birthDate":"1990-01-01","street":"Rua A","number":"1","city":"Curitiba","state":"PR",
                         "zipCode":"80000-000","password":"%s"}""".formatted(cpf, UUID.randomUUID(), PASSWORD)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID customerId = UUID.fromString(JsonPath.read(body, "$.customerId"));

        approveKyc.execute(new ApproveKycInput(customerId));
        UUID accountId = createAccount.execute(new CreateAccount.Input(customerId, "CHECKING")).accountId();
        return new Customer(customerId, cpf, login(cpf, PASSWORD), accountId);
    }

    private String login(String cpf, String password) throws Exception {
        String body = mvc.perform(post("/api/auth/login").header("X-Device-Id", TEST_DEVICE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cpf\":\"%s\",\"password\":\"%s\"}".formatted(cpf, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    /** Corpo completo do login (access + refresh), com o User-Agent do "dispositivo". */
    private String loginBody(String cpf, String device) throws Exception {
        return mvc.perform(post("/api/auth/login").header("X-Device-Id", TEST_DEVICE).contentType(MediaType.APPLICATION_JSON).header("User-Agent", device)
                        .content("{\"cpf\":\"%s\",\"password\":\"%s\"}".formatted(cpf, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"%s\"}".formatted(refreshToken)));
    }

    private String adminToken() throws Exception {
        String body = mvc.perform(post("/api/auth/admin/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"kage-admin-dev-123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    private ResultActions getAs(String token, String url) throws Exception {
        return mvc.perform(get(url).header("Authorization", "Bearer " + token));
    }

    // ---------- autenticação ----------

    @Test
    void semToken_deveResponder401NoFormatoErrorResponse() throws Exception {
        mvc.perform(get("/api/accounts/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.path").exists());
    }

    @Test
    void tokenInvalido_deveResponder401() throws Exception {
        getAs("isso.nao.vale", "/api/accounts/" + UUID.randomUUID()).andExpect(status().isUnauthorized());
    }

    @Test
    void me_deveDevolverOClienteDoToken() throws Exception {
        Customer maria = newCustomerWithAccount();

        getAs(maria.token(), "/api/auth/me")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value(maria.id().toString()))
                .andExpect(jsonPath("$.role").value("CUSTOMER"));
    }

    @Test
    void senhaErrada_deveResponder401Generico_eTravarNaQuintaTentativa() throws Exception {
        Customer maria = newCustomerWithAccount();
        String wrong = "{\"cpf\":\"%s\",\"password\":\"Errada-123\"}".formatted(maria.cpf());

        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/auth/login").header("X-Device-Id", TEST_DEVICE).contentType(MediaType.APPLICATION_JSON).content(wrong))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value("CPF ou senha inválidos"));
        }

        // travado: nem a senha certa entra
        mvc.perform(post("/api/auth/login").header("X-Device-Id", TEST_DEVICE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cpf\":\"%s\",\"password\":\"%s\"}".formatted(maria.cpf(), PASSWORD)))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void signup_deveRecusarSenhaFraca_semCriarCliente() throws Exception {
        String cpf = randomCpf();
        mvc.perform(post("/api/auth/signup").header("X-Device-Id", TEST_DEVICE).contentType(MediaType.APPLICATION_JSON).content("""
                        {"fullName":"Fraco","cpf":"%s","email":"%s@teste.com","phone":"41999991234",
                         "birthDate":"1990-01-01","street":"Rua A","number":"1","city":"Curitiba","state":"PR",
                         "zipCode":"80000-000","password":"123"}""".formatted(cpf, UUID.randomUUID())))
                .andExpect(status().isBadRequest());

        // se o cliente tivesse sido criado, o login daria "senha errada" do mesmo jeito; o que prova o
        // rollback é conseguir se cadastrar de novo com o mesmo CPF
        mvc.perform(post("/api/auth/signup").header("X-Device-Id", TEST_DEVICE).contentType(MediaType.APPLICATION_JSON).content("""
                        {"fullName":"Forte","cpf":"%s","email":"%s@teste.com","phone":"41999991234",
                         "birthDate":"1990-01-01","street":"Rua A","number":"1","city":"Curitiba","state":"PR",
                         "zipCode":"80000-000","password":"%s"}""".formatted(cpf, UUID.randomUUID(), PASSWORD)))
                .andExpect(status().isCreated());
    }

    // ---------- sessões ----------

    @Test
    void refresh_deveDarUmParNovo_eReusoDoAntigoDerrubaASessao() throws Exception {
        Customer maria = newCustomerWithAccount();
        String first = loginBody(maria.cpf(), "iPhone");
        String oldRefresh = JsonPath.read(first, "$.refreshToken");

        String second = refresh(oldRefresh).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String newAccess = JsonPath.read(second, "$.accessToken");
        String newRefresh = JsonPath.read(second, "$.refreshToken");
        getAs(newAccess, "/api/auth/me").andExpect(status().isOk());

        // a cópia antiga reaparece: sinal de roubo, a sessão inteira cai
        refresh(oldRefresh).andExpect(status().isUnauthorized());
        refresh(newRefresh).andExpect(status().isUnauthorized());
        getAs(newAccess, "/api/auth/me").andExpect(status().isUnauthorized());
    }

    @Test
    void logout_deveDerrubarOAccessTokenNaHora_semEsperarExpirar() throws Exception {
        Customer maria = newCustomerWithAccount();
        String body = loginBody(maria.cpf(), "Android");
        String access = JsonPath.read(body, "$.accessToken");

        mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + access)).andExpect(status().isNoContent());

        getAs(access, "/api/accounts/" + maria.accountId()).andExpect(status().isUnauthorized());
        refresh(JsonPath.read(body, "$.refreshToken")).andExpect(status().isUnauthorized());
        // a sessão do primeiro login (outro dispositivo) segue de pé
        getAs(maria.token(), "/api/accounts/" + maria.accountId()).andExpect(status().isOk());
    }

    @Test
    void sessoes_deveListarDispositivos_eEncerrarUmEspecifico() throws Exception {
        Customer maria = newCustomerWithAccount();           // 1ª sessão (sem User-Agent)
        String lost = loginBody(maria.cpf(), "Celular perdido");
        String lostAccess = JsonPath.read(lost, "$.accessToken");
        String lostSession = JsonPath.read(lost, "$.sessionId");

        getAs(maria.token(), "/api/auth/sessions")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.userAgent == 'Celular perdido')].current").value(false));

        mvc.perform(delete("/api/auth/sessions/" + lostSession).header("Authorization", "Bearer " + maria.token()))
                .andExpect(status().isNoContent());

        getAs(lostAccess, "/api/auth/me").andExpect(status().isUnauthorized());
        getAs(maria.token(), "/api/auth/me").andExpect(status().isOk());
    }

    @Test
    void sessaoDeOutroCliente_deveResponder404() throws Exception {
        Customer maria = newCustomerWithAccount();
        Customer joao = newCustomerWithAccount();
        String joaoSession = JsonPath.read(loginBody(joao.cpf(), "x"), "$.sessionId");

        mvc.perform(delete("/api/auth/sessions/" + joaoSession).header("Authorization", "Bearer " + maria.token()))
                .andExpect(status().isNotFound());
    }

    @Test
    void logoutAll_deveDerrubarTodosOsDispositivos() throws Exception {
        Customer maria = newCustomerWithAccount();
        String other = JsonPath.read(loginBody(maria.cpf(), "Tablet"), "$.accessToken");

        mvc.perform(post("/api/auth/logout-all").header("Authorization", "Bearer " + maria.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionsRevoked").value(2));

        getAs(maria.token(), "/api/auth/me").andExpect(status().isUnauthorized());
        getAs(other, "/api/auth/me").andExpect(status().isUnauthorized());
    }

    @Test
    void bloqueioDoCliente_deveDerrubarTodasAsSessoes() throws Exception {
        Customer maria = newCustomerWithAccount();
        getAs(maria.token(), "/api/auth/me").andExpect(status().isOk());

        mvc.perform(patch("/api/customers/" + maria.id() + "/block").header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk());

        // customer.blocked sai pelo outbox e o auth encerra as sessões de forma assíncrona
        awaitUntil(() -> {
            try {
                return mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + maria.token()))
                        .andReturn().getResponse().getStatus() == 401;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, java.time.Duration.ofSeconds(15));
    }

    // ---------- titularidade ----------

    @Test
    void cliente_deveVerAPropriaConta_eReceber404NaContaDeOutro() throws Exception {
        Customer maria = newCustomerWithAccount();
        Customer joao = newCustomerWithAccount();

        getAs(maria.token(), "/api/accounts/" + maria.accountId()).andExpect(status().isOk());
        getAs(maria.token(), "/api/accounts/" + joao.accountId())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Conta não encontrada"));
        getAs(maria.token(), "/api/accounts/" + joao.accountId() + "/statement").andExpect(status().isNotFound());
        getAs(maria.token(), "/api/customers/" + joao.id()).andExpect(status().isNotFound());
    }

    @Test
    void cliente_naoPodeMovimentarContaDeOutro() throws Exception {
        Customer maria = newCustomerWithAccount();
        Customer joao = newCustomerWithAccount();

        mvc.perform(post("/api/accounts/" + joao.accountId() + "/withdraw")
                        .header("Authorization", "Bearer " + maria.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":10.00}"))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/pix/send")
                        .header("Authorization", "Bearer " + maria.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceAccountId\":\"%s\",\"targetPixKey\":\"qualquer\",\"amount\":10.00}"
                                .formatted(joao.accountId())))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/accounts")
                        .header("Authorization", "Bearer " + maria.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"%s\",\"accountType\":\"CHECKING\"}".formatted(joao.id())))
                .andExpect(status().isNotFound());
    }

    // ---------- backoffice ----------

    @Test
    void cliente_deveReceber403NasRotasDeBackoffice() throws Exception {
        Customer maria = newCustomerWithAccount();
        String auth = "Bearer " + maria.token();

        mvc.perform(patch("/api/customers/" + maria.id() + "/kyc/approve").header("Authorization", auth))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
        mvc.perform(post("/api/accounts/" + maria.accountId() + "/deposit").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1000000.00}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/accounts/" + maria.accountId() + "/unblock").header("Authorization", auth))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/cards").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"%s\",\"accountId\":\"%s\",\"creditLimit\":999999,\"closingDay\":5,\"dueDay\":15}"
                                .formatted(maria.id(), maria.accountId())))
                .andExpect(status().isForbidden());
    }

    @Test
    void admin_deveAcessarQualquerConta_eFazerDeposito() throws Exception {
        Customer maria = newCustomerWithAccount();
        String admin = adminToken();

        getAs(admin, "/api/accounts/" + maria.accountId()).andExpect(status().isOk());
        mvc.perform(post("/api/accounts/" + maria.accountId() + "/deposit")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":250.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(250.00));

        // e a cliente enxerga o depósito no próprio extrato
        getAs(maria.token(), "/api/accounts/" + maria.accountId() + "/statement")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].type").value("DEPOSIT"));
    }

    @Test
    void adminComSenhaErrada_deveResponder401() throws Exception {
        mvc.perform(post("/api/auth/admin/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"chute\"}"))
                .andExpect(status().isUnauthorized());
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
