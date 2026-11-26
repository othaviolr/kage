-- Renegociação de dívida em atraso: o empréstimo com parcela vencida é encerrado como RENEGOTIATED e o
-- que se devia hoje (vencidas com encargos, futuras a valor presente), menos a entrada, vira um acordo
-- novo (origin = RENEGOTIATION), à taxa do contrato, sem dinheiro novo na conta.
ALTER TABLE loans DROP CONSTRAINT loans_status_check;
ALTER TABLE loans ALTER COLUMN status TYPE VARCHAR(15);
ALTER TABLE loans ADD CONSTRAINT loans_status_check CHECK (status IN ('ACTIVE', 'PAID', 'CANCELLED', 'RENEGOTIATED'));

ALTER TABLE loans ADD COLUMN origin VARCHAR(15) NOT NULL DEFAULT 'NEW' CHECK (origin IN ('NEW', 'RENEGOTIATION'));
ALTER TABLE loans ADD COLUMN renegotiated_from UUID REFERENCES loans (id);
ALTER TABLE loans ADD COLUMN down_payment NUMERIC(19, 2) CHECK (down_payment >= 0);
ALTER TABLE loans ADD CONSTRAINT loans_renegotiation_check
    CHECK ((origin = 'RENEGOTIATION') = (renegotiated_from IS NOT NULL));

-- um empréstimo é renegociado uma vez (o acordo pode ser renegociado de novo, ele mesmo)
CREATE UNIQUE INDEX loans_renegotiated_from_idx ON loans (renegotiated_from) WHERE renegotiated_from IS NOT NULL;
