-- Orçamento mensal por categoria de compra: quanto o cliente quer gastar em cada fatura.
CREATE TABLE card_category_budgets (
    card_id    UUID           NOT NULL REFERENCES cards (id),
    category   VARCHAR(20)    NOT NULL,
    amount     NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    updated_at TIMESTAMP(6)   NOT NULL,
    PRIMARY KEY (card_id, category)
);

-- Cada faixa (80% e 100%) avisa no máximo uma vez por categoria e fatura; trocar o orçamento zera.
CREATE TABLE card_budget_alerts (
    card_id         UUID         NOT NULL REFERENCES cards (id),
    category        VARCHAR(20)  NOT NULL,
    reference_month VARCHAR(7)   NOT NULL,
    threshold       SMALLINT     NOT NULL CHECK (threshold IN (80, 100)),
    alerted_at      TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (card_id, category, reference_month, threshold)
);
