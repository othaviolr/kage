-- Empréstimo pessoal: Price a juros fixos, IOF financiado (somado ao principal; o cliente recebe o que
-- pediu), CET informado, parcelas em débito automático. Um empréstimo ativo por conta.
CREATE TABLE loans (
    id            UUID           PRIMARY KEY,
    account_id    UUID           NOT NULL REFERENCES accounts (id),
    customer_id   UUID           NOT NULL REFERENCES customers (id),
    released      NUMERIC(19, 2) NOT NULL CHECK (released > 0),
    iof           NUMERIC(19, 2) NOT NULL CHECK (iof >= 0),
    financed      NUMERIC(19, 2) NOT NULL CHECK (financed = released + iof),
    monthly_rate  NUMERIC(10, 6) NOT NULL CHECK (monthly_rate >= 0),
    due_day       SMALLINT       NOT NULL CHECK (due_day BETWEEN 1 AND 28),
    cet_monthly   NUMERIC(10, 4) NOT NULL,
    cet_yearly    NUMERIC(12, 4) NOT NULL,
    status        VARCHAR(10)    NOT NULL CHECK (status IN ('ACTIVE', 'PAID', 'CANCELLED')),
    contract_date DATE           NOT NULL,
    contracted_at TIMESTAMP(6)   NOT NULL,
    closed_at     TIMESTAMP(6),
    version       BIGINT         NOT NULL,
    CONSTRAINT loans_closed_check CHECK ((status = 'ACTIVE') = (closed_at IS NULL))
);

CREATE UNIQUE INDEX loans_one_active_per_account ON loans (account_id) WHERE status = 'ACTIVE';
CREATE INDEX loans_account_idx ON loans (account_id, contracted_at DESC);

CREATE TABLE loan_installments (
    loan_id      UUID           NOT NULL REFERENCES loans (id),
    number       SMALLINT       NOT NULL CHECK (number >= 1),
    due_date     DATE           NOT NULL,
    amount       NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    principal    NUMERIC(19, 2) NOT NULL CHECK (principal >= 0),
    interest     NUMERIC(19, 2) NOT NULL CHECK (interest >= 0),
    paid         BOOLEAN        NOT NULL,
    paid_at      TIMESTAMP(6),
    paid_amount  NUMERIC(19, 2),
    late_charges NUMERIC(19, 2),
    discount     NUMERIC(19, 2),
    PRIMARY KEY (loan_id, number),
    CONSTRAINT loan_installments_paid_check CHECK (paid = (paid_at IS NOT NULL) AND paid = (paid_amount IS NOT NULL))
);

-- o débito automático procura as vencidas em aberto
CREATE INDEX loan_installments_due_idx ON loan_installments (due_date) WHERE NOT paid;

ALTER TABLE account_entries DROP CONSTRAINT account_entries_type_check;
ALTER TABLE account_entries ADD CONSTRAINT account_entries_type_check
    CHECK (type IN ('DEPOSIT', 'WITHDRAWAL', 'PIX_RECEIVED', 'PIX_SENT', 'PIX_REFUND_RECEIVED', 'PIX_REFUND_SENT',
                    'INVOICE_PAYMENT', 'SAVINGS_DEPOSIT', 'SAVINGS_WITHDRAWAL', 'BOLETO_PAYMENT', 'BOLETO_RECEIVED',
                    'LOAN_DISBURSEMENT', 'LOAN_PAYMENT', 'LOAN_RETURN'));
