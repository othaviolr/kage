package com.kage.card.infrastructure.export;

import com.kage.card.application.usecase.CardTransactions;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;

/**
 * Extrato do cartão em OFX 1.0.2 (SGML), o formato que os apps de finanças e de contabilidade importam:
 * extrato de cartão de crédito (CREDITCARDMSGSRSV1), em reais. No OFX, cobrança sai negativa
 * (TRNTYPE DEBIT) e crédito positivo — o contrário da fatura. Cada lançamento tem o id dele como
 * FITID, então importar de novo o mesmo período não duplica nada.
 *
 * Horário de Brasília sem horário de verão (UTC−3), como o servidor.
 */
public final class CardTransactionsOfx {

    public static final String CONTENT_TYPE = "application/x-ofx";
    private static final String EOL = "\r\n";
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final String TZ = "[-3:BRT]";
    private static final int MAX_NAME = 32;

    private CardTransactionsOfx() {
    }

    public static String write(CardTransactions.Output out) {
        StringBuilder ofx = new StringBuilder();
        for (String header : new String[]{"OFXHEADER:100", "DATA:OFXSGML", "VERSION:102", "SECURITY:NONE",
                "ENCODING:UTF-8", "CHARSET:NONE", "COMPRESSION:NONE", "OLDFILEUID:NONE", "NEWFILEUID:NONE"}) {
            ofx.append(header).append(EOL);
        }
        ofx.append(EOL);
        String now = date(out.generatedAt());
        ofx.append("<OFX>").append(EOL)
                .append("<SIGNONMSGSRSV1><SONRS><STATUS><CODE>0<SEVERITY>INFO</STATUS>")
                .append("<DTSERVER>").append(now).append("<LANGUAGE>POR</SONRS></SIGNONMSGSRSV1>").append(EOL)
                .append("<CREDITCARDMSGSRSV1><CCSTMTTRNRS><TRNUID>1<STATUS><CODE>0<SEVERITY>INFO</STATUS>").append(EOL)
                .append("<CCSTMTRS><CURDEF>BRL<CCACCTFROM><ACCTID>")
                .append(escape(out.cardLastDigits() != null ? out.cardLastDigits() : out.cardId().toString()))
                .append("</CCACCTFROM>").append(EOL)
                .append("<BANKTRANLIST><DTSTART>").append(date(YearMonth.parse(out.fromMonth()).atDay(1).atStartOfDay()))
                .append("<DTEND>").append(date(YearMonth.parse(out.toMonth()).atEndOfMonth().atTime(23, 59, 59)))
                .append(EOL);
        for (CardTransactions.Line l : out.lines()) {
            BigDecimal amount = l.amount().negate().setScale(2, RoundingMode.HALF_UP); // cobrança é débito
            String memo = l.installment() == null ? "" : "Parcela " + l.installment();
            if (l.virtualCard() != null) memo = (memo.isEmpty() ? "" : memo + " - ") + "Virtual: " + l.virtualCard();
            ofx.append("<STMTTRN><TRNTYPE>").append(amount.signum() < 0 ? "DEBIT" : "CREDIT")
                    .append("<DTPOSTED>").append(date(l.date()))
                    .append("<TRNAMT>").append(amount.toPlainString())
                    .append("<FITID>").append(l.id())
                    .append("<NAME>").append(escape(name(l.description())));
            if (!memo.isEmpty()) ofx.append("<MEMO>").append(escape(memo));
            ofx.append("</STMTTRN>").append(EOL);
        }
        ofx.append("</BANKTRANLIST>").append(EOL)
                .append("<LEDGERBAL><BALAMT>").append(out.total().negate().setScale(2, RoundingMode.HALF_UP).toPlainString())
                .append("<DTASOF>").append(now).append("</LEDGERBAL>").append(EOL)
                .append("</CCSTMTRS></CCSTMTTRNRS></CREDITCARDMSGSRSV1>").append(EOL)
                .append("</OFX>").append(EOL);
        return ofx.toString();
    }

    private static String date(LocalDateTime at) {
        return at.format(DATE_TIME) + TZ;
    }

    /** NAME tem até 32 caracteres no OFX 1.0.2. */
    private static String name(String description) {
        return description.length() > MAX_NAME ? description.substring(0, MAX_NAME) : description;
    }

    /** SGML: &, < e > não podem aparecer crus no valor. */
    static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
