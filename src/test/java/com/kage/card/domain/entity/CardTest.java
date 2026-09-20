package com.kage.card.domain.entity;

import com.kage.card.domain.enums.CardStatus;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.ValidationException;
import com.kage.shared.domain.valueobject.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testes do núcleo de regra de negócio do cartão: criação e validações, consumo e liberação
 * de limite, e as transições de status. Todos os cenários partem de um cartão novo com
 * limite de crédito de 1000.00, fechamento no dia 10 e vencimento no dia 20.
 */
class CardTest {

    private Card card;

    @BeforeEach
    void setUp() {
        card = Card.create(UUID.randomUUID(), UUID.randomUUID(), Money.of("1000.00"), 10, 20);
    }

    @Test
    void create_deveNascerAtivoComLimiteUtilizadoZerado() {
        assertThat(card.getStatus()).isEqualTo(CardStatus.ACTIVE);
        assertThat(card.getUsedLimit().amount()).isEqualByComparingTo("0.00");
        assertThat(card.availableLimit().amount()).isEqualByComparingTo("1000.00");
        assertThat(card.getVersion()).isNull();
    }

    @Test
    void create_deveLancarValidationException_quandoLimiteZero() {
        assertThatThrownBy(() -> Card.create(UUID.randomUUID(), UUID.randomUUID(), Money.ZERO, 10, 20))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Limite de crédito");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 29, -1})
    void create_deveLancarValidationException_quandoDiaDeFechamentoForaDoIntervalo(int day) {
        assertThatThrownBy(() -> Card.create(UUID.randomUUID(), UUID.randomUUID(), Money.of("1000.00"), day, 20))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Dia de fechamento");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 29, -1})
    void create_deveLancarValidationException_quandoDiaDeVencimentoForaDoIntervalo(int day) {
        assertThatThrownBy(() -> Card.create(UUID.randomUUID(), UUID.randomUUID(), Money.of("1000.00"), 10, day))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Dia de vencimento");
    }

    // ---------- authorizePurchase ----------

    @Test
    void authorizePurchase_deveAumentarLimiteUtilizadoEDiminuirDisponivel() {
        card.authorizePurchase(Money.of("300.00"));

        assertThat(card.getUsedLimit().amount()).isEqualByComparingTo("300.00");
        assertThat(card.availableLimit().amount()).isEqualByComparingTo("700.00");
    }

    @Test
    void authorizePurchase_devePermitirCompraExatamenteIgualAoLimiteDisponivel() {
        card.authorizePurchase(Money.of("1000.00"));

        assertThat(card.availableLimit().amount()).isEqualByComparingTo("0.00");
    }

    @Test
    void authorizePurchase_deveLancarBusinessRuleException_quandoExcedeLimiteDisponivel() {
        card.authorizePurchase(Money.of("800.00"));

        assertThatThrownBy(() -> card.authorizePurchase(Money.of("300.00")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Limite disponível insuficiente");
    }

    @Test
    void authorizePurchase_deveLancarBusinessRuleException_quandoCartaoBloqueado() {
        card.block();

        assertThatThrownBy(() -> card.authorizePurchase(Money.of("10.00")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("não está ativo");
    }

    @Test
    void authorizePurchase_deveLancarBusinessRuleException_quandoCartaoCancelado() {
        card.cancel();

        assertThatThrownBy(() -> card.authorizePurchase(Money.of("10.00")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("não está ativo");
    }

    @Test
    void authorizePurchase_deveLancarValidationException_quandoValorZero() {
        assertThatThrownBy(() -> card.authorizePurchase(Money.ZERO))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("maior que zero");
    }

    // ---------- releaseLimit ----------

    @Test
    void releaseLimit_deveDiminuirLimiteUtilizadoERestaurarDisponivel() {
        card.authorizePurchase(Money.of("600.00"));

        card.releaseLimit(Money.of("250.00"));

        assertThat(card.getUsedLimit().amount()).isEqualByComparingTo("350.00");
        assertThat(card.availableLimit().amount()).isEqualByComparingTo("650.00");
    }

    @Test
    void releaseLimit_deveLancarBusinessRuleException_quandoExcedeLimiteUtilizado() {
        card.authorizePurchase(Money.of("100.00"));

        assertThatThrownBy(() -> card.releaseLimit(Money.of("150.00")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("excede o limite utilizado");
    }

    // ---------- block / unblock / cancel ----------

    @Test
    void block_deveMudarStatusParaBloqueado() {
        card.block();

        assertThat(card.getStatus()).isEqualTo(CardStatus.BLOCKED);
    }

    @Test
    void block_deveLancarBusinessRuleException_quandoJaBloqueado() {
        card.block();

        assertThatThrownBy(() -> card.block())
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("já está bloqueado");
    }

    @Test
    void block_deveLancarBusinessRuleException_quandoCancelado() {
        card.cancel();

        assertThatThrownBy(() -> card.block())
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("já está cancelado");
    }

    @Test
    void unblock_deveVoltarStatusParaAtivo() {
        card.block();

        card.unblock();

        assertThat(card.getStatus()).isEqualTo(CardStatus.ACTIVE);
    }

    @Test
    void unblock_deveLancarBusinessRuleException_quandoNaoEstaBloqueado() {
        assertThatThrownBy(() -> card.unblock())
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("não está bloqueado");
    }

    @Test
    void cancel_deveMudarStatusParaCancelado_quandoSemLimiteUtilizado() {
        card.cancel();

        assertThat(card.getStatus()).isEqualTo(CardStatus.CANCELLED);
    }

    @Test
    void cancel_deveLancarBusinessRuleException_quandoJaCancelado() {
        card.cancel();

        assertThatThrownBy(() -> card.cancel())
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("já está cancelado");
    }

    @Test
    void cancel_deveLancarBusinessRuleException_quandoHaFaturaEmAberto() {
        card.authorizePurchase(Money.of("100.00"));

        assertThatThrownBy(() -> card.cancel())
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("fatura em aberto");
    }
}
