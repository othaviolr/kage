-- Lotes das caixinhas: um por depósito, com a própria data (IR e IOF dependem do tempo de cada real).
CREATE TABLE savings_box_lots (
                                  id uuid NOT NULL,
                                  box_id uuid NOT NULL,
                                  deposited_on date NOT NULL,
                                  -- ordem dos lotes do mesmo dia (o resgate é FIFO)
                                  position integer NOT NULL,
                                  -- quanto do que foi guardado ainda está aqui (base de custo do IR)
                                  principal numeric(19,2) NOT NULL,
                                  balance numeric(19,2) NOT NULL,
                                  pending_yield numeric(19,10) NOT NULL DEFAULT 0,
                                  CONSTRAINT savings_box_lots_pkey PRIMARY KEY (id),
                                  CONSTRAINT savings_box_lots_box_fkey FOREIGN KEY (box_id) REFERENCES savings_boxes (id),
                                  CONSTRAINT savings_box_lots_principal_check CHECK (principal >= 0),
                                  CONSTRAINT savings_box_lots_balance_check CHECK (balance >= 0)
);

CREATE INDEX savings_box_lots_box_idx ON savings_box_lots (box_id, deposited_on, position);

-- Caixinhas que já existiam viram um lote só, datado da criação. Todo o saldo conta como principal
-- (rendimento anterior não é tributado depois): na dúvida, a favor do cliente.
INSERT INTO savings_box_lots (id, box_id, deposited_on, position, principal, balance, pending_yield)
SELECT gen_random_uuid(), id, created_at::date, 0, balance, balance, pending_yield
FROM savings_boxes
WHERE status = 'ACTIVE' AND balance > 0;

-- o rendimento fracionário agora vive em cada lote
ALTER TABLE savings_boxes DROP COLUMN pending_yield;

-- resgates guardam o que foi retido: amount = bruto, net_amount = o que foi para a conta
ALTER TABLE savings_box_movements
    ADD COLUMN gain numeric(19,2),
    ADD COLUMN iof numeric(19,2),
    ADD COLUMN income_tax numeric(19,2),
    ADD COLUMN net_amount numeric(19,2),
    ADD CONSTRAINT savings_box_movements_taxes_check
        CHECK (type <> 'WITHDRAWAL' OR net_amount IS NULL OR net_amount = amount - iof - income_tax);
