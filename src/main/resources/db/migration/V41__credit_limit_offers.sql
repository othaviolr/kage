-- Limite aprovado (o teto que o banco concedeu) separado do limite em uso (o que o cliente escolheu,
-- até o aprovado). Os cartões existentes começam com os dois iguais.
ALTER TABLE cards ADD COLUMN approved_limit NUMERIC(19, 2);
UPDATE cards SET approved_limit = credit_limit;
ALTER TABLE cards ALTER COLUMN approved_limit SET NOT NULL;
ALTER TABLE cards ADD CONSTRAINT cards_limit_within_approved CHECK (credit_limit <= approved_limit);

-- Ofertas de aumento feitas pela análise de comportamento (valem 30 dias; vencida = não respondida
-- depois de expires_at)
CREATE TABLE card_limit_offers (
    id             UUID           PRIMARY KEY,
    card_id        UUID           NOT NULL REFERENCES cards (id),
    previous_limit NUMERIC(19, 2) NOT NULL,
    offered_limit  NUMERIC(19, 2) NOT NULL CHECK (offered_limit > previous_limit),
    status         VARCHAR(10)    NOT NULL CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED')),
    created_at     TIMESTAMP(6)   NOT NULL,
    expires_at     TIMESTAMP(6)   NOT NULL CHECK (expires_at > created_at),
    responded_at   TIMESTAMP(6),
    CONSTRAINT card_limit_offers_response_check CHECK ((status = 'PENDING') = (responded_at IS NULL))
);

CREATE INDEX card_limit_offers_card_idx ON card_limit_offers (card_id, created_at DESC);
