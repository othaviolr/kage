-- PIX copia e cola de cobrança do Kage (hoje, o da fatura do cartão) pago pelo PIX da conta Kage:
-- o débito e a baixa da cobrança vão na mesma transação. Uma cobrança é paga uma vez só (UNIQUE).
-- Entra na soma do limite de PIX da conta, como qualquer PIX enviado.
CREATE TABLE pix_billing_payments (
    id          UUID           PRIMARY KEY,
    account_id  UUID           NOT NULL REFERENCES accounts (id),
    charge_id   UUID           NOT NULL UNIQUE,
    kind        VARCHAR(20)    NOT NULL CHECK (kind IN ('CARD_INVOICE')),
    code        TEXT           NOT NULL,
    beneficiary VARCHAR(100)   NOT NULL,
    description VARCHAR(200)   NOT NULL,
    amount      NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    paid_at     TIMESTAMP(6)   NOT NULL
);

CREATE INDEX pix_billing_payments_account_idx ON pix_billing_payments (account_id, paid_at DESC);
