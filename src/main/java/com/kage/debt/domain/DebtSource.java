package com.kage.debt.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Porta: cada produto de crédito (cartão, empréstimo) diz o que o cliente deve nele. Só lê. */
public interface DebtSource {

    List<Debt> debtsOf(UUID customerId, LocalDate today);
}
