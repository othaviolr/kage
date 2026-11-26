package com.kage.account;

import com.jayway.jsonpath.JsonPath;
import com.kage.AbstractIntegrationTest;
import com.kage.account.application.usecase.CreateAccount;
import com.kage.account.application.usecase.DepositAccount;
import com.kage.account.application.usecase.WithdrawAccount;
import com.kage.customer.application.usecase.approvekyc.ApproveKycInput;
import com.kage.customer.application.usecase.approvekyc.ApproveKycUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Extrato pelo HTTP: saldos do período no JSON e o download em CSV, só para o dono da conta. */
class StatementExportIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Sombra-2026";

    @Autowired WebApplicationContext context;
    @Autowired ApproveKycUseCase approveKyc;
    @Autowired CreateAccount createAccount;
    @Autowired DepositAccount depositAccount;
    @Autowired WithdrawAccount withdrawAccount;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private record Customer(String token, UUID accountId) {}

    private Customer customer(String name) throws Exception {
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
        String login = mvc.perform(post("/api/auth/login").header("X-Device-Id", TEST_DEVICE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cpf\":\"%s\",\"password\":\"%s\"}".formatted(cpf, PASSWORD)))
                .andReturn().getResponse().getContentAsString();
        return new Customer(JsonPath.read(login, "$.accessToken"), accountId);
    }

    @Test
    void extratoEmCsv_comSaldoAnteriorEFinal_soParaODono() throws Exception {
        Customer ana = customer("Ana Extrato");
        Customer outro = customer("Outro Cliente");
        depositAccount.execute(new DepositAccount.Input(ana.accountId(), new BigDecimal("1500.00")));
        withdrawAccount.execute(new WithdrawAccount.Input(ana.accountId(), new BigDecimal("200.50")));
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));

        // o JSON do app também ganhou os saldos do período e a descrição de cada lançamento
        mvc.perform(get("/api/accounts/" + ana.accountId() + "/statement").header("Authorization", "Bearer " + ana.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openingBalance").value(0))
                .andExpect(jsonPath("$.closingBalance").value(1299.50))
                .andExpect(jsonPath("$.entries[0].description").value("Saque"));

        MockHttpServletResponse response = mvc.perform(get("/api/accounts/" + ana.accountId() + "/statement.csv")
                        .header("Authorization", "Bearer " + ana.token()))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        MediaType type = MediaType.parseMediaType(response.getContentType());
        assertThat(type.isCompatibleWith(MediaType.parseMediaType("text/csv"))).isTrue();
        assertThat(type.getCharset()).isEqualTo(StandardCharsets.UTF_8);
        assertThat(response.getHeader("Content-Disposition")).startsWith("attachment; filename=\"extrato-kage-");
        String csv = new String(response.getContentAsByteArray(), StandardCharsets.UTF_8);
        String[] lines = csv.substring(1).split("\r\n");
        assertThat(csv).startsWith("﻿");
        assertThat(lines[0]).isEqualTo("Data;Hora;Descrição;Valor (R$);Saldo (R$);Identificador");
        assertThat(lines[1]).endsWith(";;Saldo anterior;;0,00;");
        assertThat(lines[2]).startsWith(today + ";").contains(";Depósito;1500,00;1500,00;");
        assertThat(lines[3]).startsWith(today + ";").contains(";Saque;-200,50;1299,50;");
        assertThat(lines[4]).isEqualTo(today + ";;Saldo final;;1299,50;");

        mvc.perform(get("/api/accounts/" + ana.accountId() + "/statement.csv").header("Authorization", "Bearer " + outro.token()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/accounts/" + ana.accountId() + "/statement.csv?from=2025-01-01&to=2026-12-31")
                        .header("Authorization", "Bearer " + ana.token()))
                .andExpect(status().isBadRequest());
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
