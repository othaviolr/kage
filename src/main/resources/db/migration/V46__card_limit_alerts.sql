-- Alerta de uso do limite do cartão: cada faixa (80% e 100%) avisa no máximo uma vez por fatura.
-- A chave garante que duas compras simultâneas não mandem o mesmo aviso duas vezes.
CREATE TABLE card_limit_alerts (
    card_id         UUID         NOT NULL REFERENCES cards (id),
    reference_month VARCHAR(7)   NOT NULL,
    threshold       SMALLINT     NOT NULL CHECK (threshold IN (80, 100)),
    alerted_at      TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (card_id, reference_month, threshold)
);
