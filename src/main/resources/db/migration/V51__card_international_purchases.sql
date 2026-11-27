-- Compra em moeda estrangeira: a conversão como foi feita no dia (PTAX, spread do Kage, IOF de 3,5%).
-- Na fatura, a compra sai em reais e o IOF numa linha própria; aqui fica o detalhe para explicar o valor.
CREATE TABLE card_international_purchases (
    purchase_id    UUID           PRIMARY KEY,
    card_id        UUID           NOT NULL REFERENCES cards (id),
    currency       CHAR(3)        NOT NULL,
    foreign_amount NUMERIC(19, 2) NOT NULL CHECK (foreign_amount > 0),
    ptax           NUMERIC(19, 6) NOT NULL CHECK (ptax > 0),
    ptax_date      DATE           NOT NULL,
    spread         NUMERIC(9, 6)  NOT NULL,
    rate           NUMERIC(19, 6) NOT NULL,
    brl_amount     NUMERIC(19, 2) NOT NULL,
    iof            NUMERIC(19, 2) NOT NULL,
    purchased_at   TIMESTAMP(6)   NOT NULL
);

CREATE INDEX card_international_purchases_card_idx ON card_international_purchases (card_id);
