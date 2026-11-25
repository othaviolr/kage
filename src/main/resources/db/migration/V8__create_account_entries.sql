-- Extrato (ledger) da conta: um lançamento imutável por movimentação de saldo.
CREATE TABLE account_entries (
                                 id uuid NOT NULL,
                                 account_id uuid NOT NULL,
                                 type varchar(32) NOT NULL,
                                 amount numeric(19,2) NOT NULL,
                                 balance_after numeric(19,2) NOT NULL,
                                 reference_id uuid,
                                 created_at timestamp(6) NOT NULL,
                                 CONSTRAINT account_entries_pkey PRIMARY KEY (id),
                                 CONSTRAINT account_entries_account_fkey FOREIGN KEY (account_id) REFERENCES accounts (id),
                                 CONSTRAINT account_entries_amount_check CHECK (amount > 0),
                                 CONSTRAINT account_entries_type_check CHECK (type IN ('DEPOSIT', 'WITHDRAWAL', 'PIX_RECEIVED', 'PIX_SENT',
                                                                                       'PIX_REFUND_RECEIVED', 'PIX_REFUND_SENT'))
);

-- extrato por período e soma de limites do dia: sempre filtrados por conta + data
CREATE INDEX account_entries_account_created_idx ON account_entries (account_id, created_at);

-- as somas de limite do PIX vêm de pix_transactions (incluem PIX ainda em processamento)
CREATE INDEX pix_transactions_source_created_idx ON pix_transactions (source_account_id, created_at);
