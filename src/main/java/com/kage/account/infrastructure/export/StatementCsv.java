package com.kage.account.infrastructure.export;

import com.kage.account.application.usecase.GetStatement;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Extrato em CSV para abrir direto no Excel/LibreOffice em português: separador ';', vírgula
 * decimal, datas dd/MM/yyyy e BOM UTF-8 (sem ele o Excel lê os acentos errado). Ordem cronológica,
 * com o saldo anterior na primeira linha e o final na última — o jeito de conferir que nada faltou.
 *
 * Campo de texto que começa com =, +, - ou @ ganha um apóstrofo: célula de planilha que começa assim
 * vira fórmula (CSV injection). As descrições são do banco, mas a defesa fica para quando não forem.
 */
public final class StatementCsv {

    public static final String CONTENT_TYPE = "text/csv; charset=UTF-8";
    private static final String BOM = "﻿";
    private static final String EOL = "\r\n";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private StatementCsv() {
    }

    public static String write(GetStatement.Output statement) {
        StringBuilder csv = new StringBuilder(BOM);
        line(csv, "Data", "Hora", "Descrição", "Valor (R$)", "Saldo (R$)", "Identificador");
        line(csv, statement.from().format(DATE), "", "Saldo anterior", "", money(statement.openingBalance()), "");

        List<GetStatement.EntryOutput> chronological = new ArrayList<>(statement.entries());
        java.util.Collections.reverse(chronological); // o extrato vem dos mais recentes para os mais antigos
        for (GetStatement.EntryOutput e : chronological) {
            line(csv, e.createdAt().format(DATE), e.createdAt().format(TIME), e.description(), money(e.amount()),
                    money(e.balanceAfter()), e.referenceId() == null ? e.entryId().toString() : e.referenceId().toString());
        }

        line(csv, statement.to().format(DATE), "", "Saldo final", "", money(statement.closingBalance()), "");
        return csv.toString();
    }

    /** Nome do arquivo: extrato-kage-0001-12345678-9-2026-01-01-a-2026-12-31.csv */
    public static String fileName(GetStatement.Output statement) {
        return "extrato-kage-" + statement.branch() + "-" + statement.accountNumber() + "-" + statement.from()
                + "-a-" + statement.to() + ".csv";
    }

    private static String money(BigDecimal value) {
        DecimalFormat format = new DecimalFormat("0.00", DecimalFormatSymbols.getInstance(Locale.forLanguageTag("pt-BR")));
        return format.format(value);
    }

    private static void line(StringBuilder csv, String... fields) {
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) csv.append(';');
            csv.append(escape(fields[i]));
        }
        csv.append(EOL);
    }

    private static String escape(String field) {
        if (field == null || field.isEmpty()) return "";
        String value = field;
        // número (valor e saldo) pode começar com '-'; texto que começa com sinal de fórmula, não
        if (value.matches("^[=+@].*") || (value.startsWith("-") && !value.matches("^-\\d+(,\\d+)?$"))) {
            value = "'" + value;
        }
        if (value.contains(";") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            value = "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
