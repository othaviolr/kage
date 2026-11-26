-- Contestação de compra no cartão: enquanto está em análise, a compra não é cobrada (as parcelas
-- saem das faturas abertas e o que já tinha sido cobrado volta como crédito na fatura atual).
-- Procedente, fica assim; improcedente, a compra volta a ser cobrada.
CREATE TABLE card_disputes (
    id               UUID           PRIMARY KEY,
    card_id          UUID           NOT NULL REFERENCES cards (id),
    purchase_id      UUID           NOT NULL,
    description      VARCHAR(255)   NOT NULL,
    amount           NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    credited_amount  NUMERIC(19, 2) NOT NULL CHECK (credited_amount >= 0 AND credited_amount <= amount),
    credit_month     VARCHAR(7),
    reason           VARCHAR(20)    NOT NULL CHECK (reason IN
                         ('NOT_RECOGNIZED', 'DUPLICATE_CHARGE', 'NOT_RECEIVED', 'CANCELLED_PURCHASE')),
    details          VARCHAR(500),
    status           VARCHAR(10)    NOT NULL CHECK (status IN ('OPEN', 'ACCEPTED', 'REJECTED')),
    card_blocked     BOOLEAN        NOT NULL,
    opened_at        TIMESTAMP(6)   NOT NULL,
    resolved_at      TIMESTAMP(6),
    resolution_note  VARCHAR(500),
    reversal_month   VARCHAR(7),
    -- uma compra só é contestada uma vez (a resolução é definitiva)
    CONSTRAINT card_disputes_purchase_uq UNIQUE (card_id, purchase_id),
    CONSTRAINT card_disputes_resolution_check CHECK ((status = 'OPEN') = (resolved_at IS NULL))
);

CREATE INDEX card_disputes_card_idx ON card_disputes (card_id, opened_at DESC);

ALTER TABLE invoice_items DROP CONSTRAINT invoice_items_kind_check;
ALTER TABLE invoice_items ADD CONSTRAINT invoice_items_kind_check
    CHECK (kind IN ('PURCHASE', 'PREVIOUS_BALANCE', 'REVOLVING_INTEREST', 'LATE_FINE', 'LATE_INTEREST',
                    'INVOICE_INSTALLMENT', 'ANTICIPATION', 'DISPUTE_CREDIT', 'DISPUTE_REVERSAL', 'CREDIT_BALANCE'));
