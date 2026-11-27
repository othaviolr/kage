package com.kage.card.infrastructure.export;

import com.kage.card.application.usecase.CardTransactions;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Extrato do cartão em CSV, no mesmo padrão do extrato da conta: ';', vírgula decimal, datas
 * dd/MM/yyyy, BOM UTF-8 (para o Excel ler os acentos) e proteção contra fórmula (CSV injection). No fim,
 * o total de cada tipo e o geral — o jeito de conferir que nada faltou.
 */
public final class CardTransactionsCsv {

    public static final String CONTENT_TYPE = "text/csv; charset=UTF-8";
    private static final String BOM = "﻿";
    private static final String EOL = "\r\n";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private CardTransactionsCsv() {
    }

    public static String write(CardTransactions.Output out) {
        StringBuilder csv = new StringBuilder(BOM);
        line(csv, "Data", "Fatura", "Descrição", "Parcela", "Cartão", "Tipo", "Categoria", "Valor (R$)");
        for (CardTransactions.Line l : out.lines()) {
            line(csv, l.date().format(DATE), month(l.invoiceMonth()), l.description(),
                    l.installment() == null ? "" : l.installment(), l.virtualCard() == null ? "" : "Virtual: " + l.virtualCard(),
                    CardTransactions.Group.valueOf(l.group()).label(), l.categoryLabel() == null ? "" : l.categoryLabel(),
                    money(l.amount()));
        }
        for (CardTransactions.Total t : out.totals()) {
            line(csv, "", "", "Total — " + t.label() + " (" + t.count() + ")", "", "", "", "", money(t.amount()));
        }
        line(csv, "", "", "Total do período", "", "", "", "", money(out.total()));
        return csv.toString();
    }

    /** extrato-cartao-kage-2034-01-a-2034-06.csv */
    public static String fileName(CardTransactions.Output out, String extension) {
        return "extrato-cartao-kage-" + out.fromMonth() + "-a-" + out.toMonth() + "." + extension;
    }

    private static String month(String referenceMonth) {
        return referenceMonth.substring(5) + "/" + referenceMonth.substring(0, 4);
    }

    private static String money(BigDecimal value) {
        return new DecimalFormat("0.00", DecimalFormatSymbols.getInstance(Locale.forLanguageTag("pt-BR"))).format(value);
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
        // valor negativo (crédito) começa com '-'; texto que começa com sinal de fórmula, não
        if (value.matches("^[=+@].*") || (value.startsWith("-") && !value.matches("^-\\d+(,\\d+)?$"))) {
            value = "'" + value;
        }
        if (value.contains(";") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            value = "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
