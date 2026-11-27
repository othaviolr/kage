-- Pagar a fatura de outro banco: boleto ou PIX copia e cola gerado para a fatura. O pagamento só entra
-- na fatura quando o dinheiro chega (compensação do boleto, liquidação do PIX).
CREATE SEQUENCE card_invoice_charge_our_number_seq;

CREATE TABLE card_invoice_charges (
    id              UUID           PRIMARY KEY,
    card_id         UUID           NOT NULL REFERENCES cards (id),
    invoice_id      UUID           NOT NULL REFERENCES invoices (id),
    reference_month VARCHAR(7)     NOT NULL,
    method          VARCHAR(10)    NOT NULL CHECK (method IN ('BOLETO', 'PIX')),
    amount          NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    expires_at      TIMESTAMP(6)   NOT NULL,
    code            VARCHAR(512)   NOT NULL UNIQUE,
    reference       VARCHAR(25)    NOT NULL UNIQUE,
    status          VARCHAR(10)    NOT NULL CHECK (status IN ('PENDING', 'PAID', 'CANCELLED')),
    paid_at         TIMESTAMP(6),
    created_at      TIMESTAMP(6)   NOT NULL,
    CHECK ((status = 'PAID') = (paid_at IS NOT NULL))
);

CREATE INDEX card_invoice_charges_invoice_idx ON card_invoice_charges (invoice_id, created_at DESC);
