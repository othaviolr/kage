package com.kage.card.application.usecase;

import com.kage.card.domain.entity.Card;
import com.kage.card.domain.repository.LimitAlertRepository;
import com.kage.notification.application.usecase.NotificationTemplates;
import com.kage.shared.domain.valueobject.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class LimitUsageAlertsTest {

    @Mock CardEventPublisher publisher;

    private final YearMonth march = YearMonth.of(2034, 3);
    private final Set<String> alerted = new HashSet<>();
    private LimitUsageAlerts alerts;
    private Card card;

    /** Repositório em memória com a mesma regra da chave primária. */
    private final LimitAlertRepository repository = (cardId, month, threshold, at) ->
            alerted.add(cardId + "|" + month + "|" + threshold);

    @BeforeEach
    void setUp() {
        alerts = new LimitUsageAlerts(repository, publisher);
        card = Card.create(UUID.randomUUID(), UUID.randomUUID(), Money.of("1000.00"), 10, 20);
    }

    private void buy(String amount) {
        card.authorizePurchase(Money.of(amount));
        alerts.afterPurchase(card, march);
    }

    @Test
    void abaixoDe80_naoAvisa() {
        buy("799.99");
        verify(publisher, never()).publishLimitUsageAlert(any(), anyString(), anyInt(), anyInt(), any(), any(), any(), any());
    }

    @Test
    void passaDe80_avisaUmaVez_eDepoisOs100() {
        buy("700.00");
        buy("150.00"); // 85%
        verify(publisher).publishLimitUsageAlert(card.getId(), "2034-03", 80, 85, Money.of("850.00"),
                Money.of("1000.00"), Money.of("1000.00"), Money.of("150.00"));

        buy("50.00"); // 90%: os 80% já foram avisados nesta fatura
        buy("100.00"); // 100%
        verify(publisher).publishLimitUsageAlert(card.getId(), "2034-03", 100, 100, Money.of("1000.00"),
                Money.of("1000.00"), Money.of("1000.00"), Money.ZERO);
        verify(publisher, org.mockito.Mockito.times(2))
                .publishLimitUsageAlert(any(), anyString(), anyInt(), anyInt(), any(), any(), any(), any());
    }

    @Test
    void pulaDireto_para100_soOAvisoDe100_eOs80NaoChegamDepois() {
        buy("1000.00");
        verify(publisher).publishLimitUsageAlert(eq(card.getId()), eq("2034-03"), eq(100), eq(100), any(), any(), any(), any());

        card.releaseLimit(Money.of("300.00")); // pagou parte: 70%
        buy("150.00"); // 85% de novo, mesma fatura
        verify(publisher, never()).publishLimitUsageAlert(any(), anyString(), eq(80), anyInt(), any(), any(), any(), any());
    }

    @Test
    void faturaSeguinte_avisaDeNovo() {
        buy("850.00");
        card.releaseLimit(Money.of("850.00"));
        card.authorizePurchase(Money.of("900.00"));
        alerts.afterPurchase(card, march.plusMonths(1));
        verify(publisher).publishLimitUsageAlert(eq(card.getId()), eq("2034-03"), eq(80), anyInt(), any(), any(), any(), any());
        verify(publisher).publishLimitUsageAlert(eq(card.getId()), eq("2034-04"), eq(80), eq(90), any(), any(), any(), any());
    }

    @Test
    void textos() {
        var warning = NotificationTemplates.limitUsageAlert(80, 85, new BigDecimal("1000.00"),
                new BigDecimal("1000.00"), new BigDecimal("150.00"));
        assertThat(warning.title()).isEqualTo("Você já usou 85% do limite");
        assertThat(warning.message()).isEqualTo("Restam R$ 150,00 de R$ 1.000,00 no seu cartão.");

        var exhausted = NotificationTemplates.limitUsageAlert(100, 100, new BigDecimal("1000.00"),
                new BigDecimal("3000.00"), BigDecimal.ZERO);
        assertThat(exhausted.title()).isEqualTo("Seu limite acabou");
        assertThat(exhausted.message()).isEqualTo("Todo o limite de R$ 1.000,00 do seu cartão está em uso: novas compras "
                + "vão ser recusadas até você pagar a fatura ou antecipar parcelas. Se precisar, dá para subir o limite "
                + "até R$ 3.000,00 pelo app.");
    }
}
