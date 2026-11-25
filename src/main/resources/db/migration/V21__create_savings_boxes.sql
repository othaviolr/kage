-- Caixinhas: dinheiro separado da conta, rendendo 100% do CDI por dia útil.
CREATE TABLE savings_boxes (
                               id uuid NOT NULL,
                               customer_id uuid NOT NULL,
                               account_id uuid NOT NULL,
                               name varchar(60) NOT NULL,
                               goal_amount numeric(19,2),
                               balance numeric(19,2) NOT NULL DEFAULT 0,
                               -- rendimento acumulado ainda menor que um centavo (10 casas)
                               pending_yield numeric(19,10) NOT NULL DEFAULT 0,
                               total_yield numeric(19,2) NOT NULL DEFAULT 0,
                               status varchar(16) NOT NULL,
                               last_yield_date date NOT NULL,
                               goal_reached_at timestamp(6),
                               created_at timestamp(6) NOT NULL,
                               updated_at timestamp(6) NOT NULL,
                               closed_at timestamp(6),
                               version bigint NOT NULL DEFAULT 0,
                               CONSTRAINT savings_boxes_pkey PRIMARY KEY (id),
                               CONSTRAINT savings_boxes_account_fkey FOREIGN KEY (account_id) REFERENCES accounts (id),
                               CONSTRAINT savings_boxes_status_check CHECK (status IN ('ACTIVE', 'CLOSED')),
                               CONSTRAINT savings_boxes_balance_check CHECK (balance >= 0),
                               CONSTRAINT savings_boxes_closed_empty_check CHECK (status = 'ACTIVE' OR balance = 0),
                               CONSTRAINT savings_boxes_goal_check CHECK (goal_amount IS NULL OR goal_amount > 0)
);

CREATE INDEX savings_boxes_account_idx ON savings_boxes (account_id) WHERE status = 'ACTIVE';
CREATE INDEX savings_boxes_yield_idx ON savings_boxes (last_yield_date) WHERE status = 'ACTIVE';

-- extrato da caixinha
CREATE TABLE savings_box_movements (
                                       id uuid NOT NULL,
                                       box_id uuid NOT NULL,
                                       type varchar(16) NOT NULL,
                                       amount numeric(19,2) NOT NULL,
                                       balance_after numeric(19,2) NOT NULL,
                                       created_at timestamp(6) NOT NULL,
                                       CONSTRAINT savings_box_movements_pkey PRIMARY KEY (id),
                                       CONSTRAINT savings_box_movements_box_fkey FOREIGN KEY (box_id) REFERENCES savings_boxes (id),
                                       CONSTRAINT savings_box_movements_type_check CHECK (type IN ('DEPOSIT', 'WITHDRAWAL', 'YIELD')),
                                       CONSTRAINT savings_box_movements_amount_check CHECK (amount > 0)
);

CREATE INDEX savings_box_movements_box_created_idx ON savings_box_movements (box_id, created_at DESC);

-- guardar/resgatar aparecem no extrato da conta
ALTER TABLE account_entries DROP CONSTRAINT account_entries_type_check;
ALTER TABLE account_entries ADD CONSTRAINT account_entries_type_check
    CHECK (type IN ('DEPOSIT', 'WITHDRAWAL', 'PIX_RECEIVED', 'PIX_SENT', 'PIX_REFUND_RECEIVED', 'PIX_REFUND_SENT',
                    'INVOICE_PAYMENT', 'SAVINGS_DEPOSIT', 'SAVINGS_WITHDRAWAL'));
