-- Pedidos de aumento de limite (Resolução BCB nº 142, para o PIX; aqui vale para todos os limites da
-- conta): diminuir vale na hora; aumentar só depois de 24h, para quem pegou o celular de alguém não
-- conseguir subir o limite e esvaziar a conta na mesma noite.
CREATE TABLE account_limit_requests (
    id               UUID           PRIMARY KEY,
    account_id       UUID           NOT NULL REFERENCES accounts (id),
    limit_type       VARCHAR(20)    NOT NULL CHECK (limit_type IN
                         ('PIX_DAILY', 'PIX_NIGHT', 'DAILY_WITHDRAWAL', 'DAILY_TRANSFER', 'MONTHLY_TRANSFER')),
    previous_amount  NUMERIC(19, 2) NOT NULL,
    requested_amount NUMERIC(19, 2) NOT NULL CHECK (requested_amount > previous_amount),
    requested_at     TIMESTAMP(6)   NOT NULL,
    effective_at     TIMESTAMP(6)   NOT NULL CHECK (effective_at > requested_at),
    status           VARCHAR(10)    NOT NULL CHECK (status IN ('PENDING', 'APPLIED', 'CANCELLED', 'REPLACED')),
    resolved_at      TIMESTAMP(6)
);

-- um pedido pendente por limite: pedir de novo substitui o anterior
CREATE UNIQUE INDEX account_limit_requests_pending_uq ON account_limit_requests (account_id, limit_type)
    WHERE status = 'PENDING';
-- o job procura os pendentes que já venceram
CREATE INDEX account_limit_requests_due_idx ON account_limit_requests (effective_at) WHERE status = 'PENDING';
