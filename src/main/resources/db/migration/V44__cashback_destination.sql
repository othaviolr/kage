-- O cliente escolhe para onde vai o cashback: abater a fatura seguinte (padrão) ou cair na conta.
ALTER TABLE cards ADD COLUMN cashback_destination VARCHAR(10) NOT NULL DEFAULT 'INVOICE'
    CHECK (cashback_destination IN ('INVOICE', 'ACCOUNT'));

-- Cashback que caiu na conta: um por fatura de origem (a chave garante que não credita duas vezes).
-- O que vai para a fatura continua sendo a linha CASHBACK da fatura seguinte.
CREATE TABLE card_cashback_account_credits (
    source_invoice_id UUID           PRIMARY KEY REFERENCES invoices (id),
    card_id           UUID           NOT NULL REFERENCES cards (id),
    amount            NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    credited_at       TIMESTAMP(6)   NOT NULL
);

CREATE INDEX card_cashback_account_credits_card_idx ON card_cashback_account_credits (card_id);

ALTER TABLE account_entries DROP CONSTRAINT account_entries_type_check;
ALTER TABLE account_entries ADD CONSTRAINT account_entries_type_check
    CHECK (type IN ('DEPOSIT', 'WITHDRAWAL', 'PIX_RECEIVED', 'PIX_SENT', 'PIX_REFUND_RECEIVED', 'PIX_REFUND_SENT',
                    'INVOICE_PAYMENT', 'SAVINGS_DEPOSIT', 'SAVINGS_WITHDRAWAL', 'BOLETO_PAYMENT', 'BOLETO_RECEIVED',
                    'LOAN_DISBURSEMENT', 'LOAN_PAYMENT', 'LOAN_RETURN', 'CARD_CASHBACK'));
