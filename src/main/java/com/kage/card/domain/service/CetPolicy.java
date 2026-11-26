package com.kage.card.domain.service;

import com.kage.shared.domain.valueobject.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Custo Efetivo Total (Res. CMN 3.517/2007): a taxa anual que iguala o valor liberado ao cliente aos
 * pagamentos que ele faz — juros, IOF e tarifas (o Kage não cobra tarifa) —, com o prazo de cada
 * pagamento em dias/365. É o número que permite comparar operações diferentes.
 */
public final class CetPolicy {

    public record Flow(LocalDate date, Money amount) {}

    /** @param monthly e @param yearly frações (0,1234 = 12,34%) */
    public record Cet(BigDecimal monthly, BigDecimal yearly) {}

    private CetPolicy() {
    }

    /** CET de quem recebe {@code released} em {@code start} e paga {@code payments}. */
    public static Cet of(Money released, LocalDate start, List<Flow> payments) {
        double principal = released.amount().doubleValue();
        // Σ pagamento / (1 + cet)^(dias/365) = liberado; o lado esquerdo cai quando o cet sobe: bisseção
        double low = 0, high = 1;
        while (presentValue(payments, start, high) > principal && high < 1e6) high *= 2;
        for (int i = 0; i < 200; i++) {
            double mid = (low + high) / 2;
            if (presentValue(payments, start, mid) > principal) low = mid;
            else high = mid;
        }
        double yearly = (low + high) / 2;
        double monthly = Math.pow(1 + yearly, 1.0 / 12) - 1;
        return new Cet(BigDecimal.valueOf(monthly).setScale(4, RoundingMode.HALF_UP),
                BigDecimal.valueOf(yearly).setScale(4, RoundingMode.HALF_UP));
    }

    /** CET de referência do rotativo: R$ 1.000 que giram por 30 dias, com os juros do mês e o IOF. */
    public static Cet revolvingReference(LocalDate start) {
        Money balance = Money.of("1000.00");
        Money interest = Money.of(balance.amount().multiply(RevolvingCreditPolicy.REVOLVING_MONTHLY_RATE));
        Money due = balance.add(interest).add(IofPolicy.revolving(balance, 30));
        return of(balance, start, List.of(new Flow(start.plusDays(30), due)));
    }

    /** CET de referência do parcelamento da fatura: R$ 1.000 em {@code installments}x, parcelas mensais. */
    public static Cet installmentReference(LocalDate start, int installments) {
        Money principal = Money.of("1000.00");
        InstallmentPolicy.Quote quote = InstallmentPolicy.quoteInvoice(principal, installments);
        List<LocalDate> dueDates = new ArrayList<>();
        for (int k = 1; k <= installments; k++) dueDates.add(start.plusMonths(k));
        return installment(quote, start, dueDates);
    }

    /** CET de um parcelamento: parcelas nas datas de vencimento, IOF pago junto da primeira. */
    public static Cet installment(InstallmentPolicy.Quote quote, LocalDate start, List<LocalDate> dueDates) {
        Money iof = IofPolicy.installments(quote.principal(), InstallmentPolicy.amortizations(quote), days(start, dueDates));
        List<Flow> flows = new ArrayList<>();
        for (int k = 0; k < quote.installments(); k++) {
            Money amount = quote.amounts().get(k);
            flows.add(new Flow(dueDates.get(k), k == 0 ? amount.add(iof) : amount));
        }
        return of(quote.principal(), start, flows);
    }

    public static List<Long> days(LocalDate start, List<LocalDate> dueDates) {
        return dueDates.stream().map(d -> ChronoUnit.DAYS.between(start, d)).toList();
    }

    private static double presentValue(List<Flow> payments, LocalDate start, double rate) {
        double total = 0;
        for (Flow flow : payments) {
            double years = ChronoUnit.DAYS.between(start, flow.date()) / 365.0;
            total += flow.amount().amount().doubleValue() / Math.pow(1 + rate, years);
        }
        return total;
    }
}
