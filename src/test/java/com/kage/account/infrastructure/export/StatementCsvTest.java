package com.kage.account.infrastructure.export;

import com.kage.account.application.usecase.GetStatement;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StatementCsvTest {

    private static final UUID PIX = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID DEPOSIT = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    private static GetStatement.Output statement(String description) {
        return new GetStatement.Output(UUID.randomUUID(), "0001", "12345678-9", LocalDate.of(2026, 11, 1),
                LocalDate.of(2026, 11, 30), new BigDecimal("1000.00"), new BigDecimal("2169.50"), new BigDecimal("2169.50"),
                List.of( // mais recentes primeiro, como o extrato devolve
                        new GetStatement.EntryOutput(UUID.randomUUID(), "PIX_SENT", description, new BigDecimal("-30.50"),
                                new BigDecimal("2169.50"), PIX, LocalDateTime.of(2026, 11, 24, 21, 5, 9)),
                        new GetStatement.EntryOutput(DEPOSIT, "DEPOSIT", "Depósito", new BigDecimal("1200.00"),
                                new BigDecimal("2200.00"), null, LocalDateTime.of(2026, 11, 3, 9, 0))));
    }

    @Test
    void formatoDoExcelEmPortugues_emOrdemCronologica_comSaldoAnteriorEFinal() {
        String csv = StatementCsv.write(statement("PIX enviado"));

        assertThat(csv).startsWith("﻿"); // BOM: sem ele o Excel estraga os acentos
        assertThat(csv.substring(1).split("\r\n")).containsExactly(
                "Data;Hora;Descrição;Valor (R$);Saldo (R$);Identificador",
                "01/11/2026;;Saldo anterior;;1000,00;",
                "03/11/2026;09:00:00;Depósito;1200,00;2200,00;" + DEPOSIT,
                "24/11/2026;21:05:09;PIX enviado;-30,50;2169,50;" + PIX,
                "30/11/2026;;Saldo final;;2169,50;");
    }

    @Test
    void textoQueViraFormula_ganhaApostrofo_eSeparadorVaiEntreAspas() {
        String formula = StatementCsv.write(statement("=HYPERLINK(\"http://golpe\")"));
        assertThat(formula).contains(";\"'=HYPERLINK(\"\"http://golpe\"\")\";");

        String semicolon = StatementCsv.write(statement("PIX; aluguel"));
        assertThat(semicolon).contains(";\"PIX; aluguel\";");

        String negative = StatementCsv.write(statement("-texto"));
        assertThat(negative).contains(";'-texto;").contains(";-30,50;"); // número negativo continua número
    }

    @Test
    void nomeDoArquivo_temContaEPeriodo() {
        assertThat(StatementCsv.fileName(statement("x"))).isEqualTo("extrato-kage-0001-12345678-9-2026-11-01-a-2026-11-30.csv");
    }
}
