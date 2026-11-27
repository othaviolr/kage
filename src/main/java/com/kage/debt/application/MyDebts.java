package com.kage.debt.application;

import com.kage.debt.domain.Debt;
import com.kage.debt.domain.DebtKind;
import com.kage.debt.domain.DebtSource;
import com.kage.card.domain.service.RevolvingCreditPolicy;
import com.kage.shared.domain.DomainTime;
import com.kage.shared.domain.exception.NotFoundException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * "Minhas dívidas": tudo o que o cliente deve no Kage numa tela — faturas, rotativo, parcelamentos e
 * empréstimos —, com o custo de cada uma (juros ao mês e CET) e quanto custa quitar hoje. A lista vem
 * na ordem em que vale a pena pagar: o que está vencido, depois o mais caro.
 *
 * Só lê: nada aqui paga, antecipa ou renegocia. Cada dívida diz qual API de origem resolve
 * ({@code referenceId}).
 */
public class MyDebts {

    /** Janela do "vence nos próximos dias". */
    static final int UPCOMING_DAYS = 30;

    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM");

    /**
     * @param outstanding        o que falta pagar seguindo o plano
     * @param payoffToday        quanto custa quitar hoje
     * @param savingsIfPaidToday juros que deixam de ser pagos quitando hoje
     * @param lateCharges        multa e mora que quitar hoje já inclui
     * @param monthlyRate        juros ao mês (fração); zero = sem juros
     * @param cetYearly          CET ao ano (fração); nulo sem juros
     * @param note               o que o cliente precisa saber sobre esta dívida
     */
    public record Item(String kind, UUID referenceId, UUID cardId, String description, BigDecimal outstanding,
                       BigDecimal payoffToday, BigDecimal savingsIfPaidToday, BigDecimal lateCharges,
                       LocalDate nextDueDate, BigDecimal nextAmount, Integer remainingInstallments,
                       BigDecimal monthlyRate, BigDecimal cetYearly, boolean overdue, String note) {}

    /**
     * @param withInterest parte do {@code outstanding} em dívidas com juros correndo
     * @param dueSoon      o que vence nos próximos {@value #UPCOMING_DAYS} dias (o próximo vencimento de cada dívida)
     */
    public record Totals(BigDecimal outstanding, BigDecimal payoffToday, BigDecimal withInterest, BigDecimal dueSoon) {}

    public record Output(UUID customerId, LocalDate asOf, Totals totals, String advice, List<Item> debts) {}

    private final List<DebtSource> sources;
    private final Predicate<UUID> customerExists;

    public MyDebts(List<DebtSource> sources, Predicate<UUID> customerExists) {
        this.sources = sources;
        this.customerExists = customerExists;
    }

    public Output execute(UUID customerId) {
        if (!customerExists.test(customerId)) throw new NotFoundException("Cliente não encontrado");
        LocalDate today = DomainTime.today();

        List<Debt> debts = new ArrayList<>();
        sources.forEach(source -> debts.addAll(source.debtsOf(customerId, today)));
        debts.sort(Comparator.comparing(Debt::overdue).reversed()
                .thenComparing(Debt::monthlyRate, Comparator.reverseOrder())
                .thenComparing(Debt::nextDueDate, Comparator.nullsLast(Comparator.naturalOrder())));

        List<Item> items = debts.stream().map(MyDebts::toItem).toList();
        LocalDate soon = today.plusDays(UPCOMING_DAYS);
        Totals totals = new Totals(
                sum(debts.stream().map(Debt::outstanding).toList()),
                sum(debts.stream().map(Debt::payoffToday).toList()),
                sum(debts.stream().filter(Debt::hasInterest).map(Debt::outstanding).toList()),
                sum(debts.stream().filter(d -> d.nextDueDate() != null && !d.nextDueDate().isAfter(soon))
                        .map(Debt::nextAmount).toList()));
        return new Output(customerId, today, totals, advice(debts), items);
    }

    private static Item toItem(Debt d) {
        BigDecimal difference = d.outstanding().subtract(d.payoffToday());
        return new Item(d.kind().name(), d.referenceId(), d.cardId(), d.description(), d.outstanding(), d.payoffToday(),
                difference.signum() > 0 ? difference : BigDecimal.ZERO,
                difference.signum() < 0 ? difference.negate() : BigDecimal.ZERO,
                d.nextDueDate(), d.nextAmount(), d.remainingInstallments(), d.monthlyRate(), d.cetYearly(), d.overdue(),
                note(d));
    }

    static String note(Debt d) {
        return switch (d.kind()) {
            case CARD_INVOICE -> invoiceNote(d);
            case CARD_CURRENT_INVOICE -> "Fecha em " + date(d.closingDate()) + " e vence em " + date(d.nextDueDate())
                    + ". Sem juros se paga até o vencimento.";
            case CARD_PURCHASE_INSTALLMENTS -> installments(d.remainingInstallments()) + " sem juros; a próxima, de "
                    + money(d.nextAmount()) + ", vem na fatura que vence em " + date(d.nextDueDate()) + ".";
            case CARD_INVOICE_FINANCING -> installments(d.remainingInstallments()) + " a " + cost(d)
                    + ". Antecipando todas hoje, você paga " + money(d.payoffToday()) + discount(d, "a menos de juros")
                    + ".";
            case LOAN, LOAN_AGREEMENT -> loanNote(d);
        };
    }

    private static String invoiceNote(Debt d) {
        String revolvingRate = percent(RevolvingCreditPolicy.REVOLVING_MONTHLY_RATE) + " a.m.";
        if (d.overdue()) {
            return "Venceu em " + date(d.nextDueDate()) + ": no próximo fechamento, o que faltar vai para o rotativo ("
                    + revolvingRate + "), com multa e mora se o pago ficar abaixo do mínimo de "
                    + money(d.minimumPayment()) + ".";
        }
        if (d.hasInterest()) {
            return "Inclui o rotativo da fatura anterior, a " + cost(d) + ". Pagando " + money(d.outstanding())
                    + " até " + date(d.nextDueDate()) + ", os juros param.";
        }
        return "Pagando " + money(d.outstanding()) + " até " + date(d.nextDueDate()) + ", não há juros. Pagando só o "
                + "mínimo (" + money(d.minimumPayment()) + "), o resto vai para o rotativo, a " + revolvingRate;
    }

    private static String loanNote(Debt d) {
        if (d.overdue()) {
            return (d.overdueInstallments() == 1 ? "1 parcela vencida" : d.overdueInstallments() + " parcelas vencidas")
                    + ", com multa de 2% e mora de 1% a.m. Para quitar tudo hoje: " + money(d.payoffToday()) + "."
                    + (d.kind() == DebtKind.LOAN ? " Dá para renegociar pelo app." : "");
        }
        return installments(d.remainingInstallments()) + " a " + cost(d) + "; a próxima, de " + money(d.nextAmount())
                + ", vence em " + date(d.nextDueDate()) + ". Quitando hoje, você paga " + money(d.payoffToday())
                + discount(d, "de desconto nos juros") + ".";
    }

    private static String advice(List<Debt> debts) {
        if (debts.isEmpty()) return "Você não deve nada no Kage.";
        Debt overdue = debts.stream().filter(Debt::overdue).findFirst().orElse(null);
        if (overdue != null) {
            return "Comece pelo que está vencido: " + overdue.description() + ". Atraso gera multa e juros de mora.";
        }
        Debt costliest = debts.get(0); // a lista já vem do mais caro para o mais barato
        if (costliest.hasInterest()) {
            return "A mais cara é " + lowerFirst(costliest.description()) + ", a " + cost(costliest)
                    + ": se sobrar dinheiro, comece por ela.";
        }
        return "Nenhuma dívida com juros: pagando as faturas até o vencimento, continua assim.";
    }

    private static String cost(Debt d) {
        String rate = percent(d.monthlyRate()) + " a.m.";
        return d.cetYearly() == null ? rate : rate + " (CET de " + percent(d.cetYearly()) + " a.a.)";
    }

    private static String discount(Debt d, String label) {
        BigDecimal saved = d.outstanding().subtract(d.payoffToday());
        return saved.signum() > 0 ? " — " + money(saved) + " " + label : "";
    }

    private static String installments(Integer n) {
        return n == null || n == 1 ? "1 parcela" : n + " parcelas";
    }

    private static String lowerFirst(String text) {
        return Character.toLowerCase(text.charAt(0)) + text.substring(1);
    }

    private static BigDecimal sum(List<BigDecimal> values) {
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    static String money(BigDecimal amount) {
        // o NumberFormat põe espaço não separável depois do "R$"
        return NumberFormat.getCurrencyInstance(PT_BR).format(amount).replace(' ', ' ');
    }

    /** 0,0899 → "8,99%"; 0,14 → "14%". */
    static String percent(BigDecimal fraction) {
        DecimalFormat format = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(PT_BR));
        return format.format(fraction.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP)) + "%";
    }

    private static String date(LocalDate date) {
        return date.format(DATE);
    }
}
