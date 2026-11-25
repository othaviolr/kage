-- Boletos emitidos por clientes do Kage (para receber).
CREATE SEQUENCE boleto_our_number_seq START WITH 1;

CREATE TABLE boletos (
                         id uuid NOT NULL,
                         beneficiary_account_id uuid NOT NULL,
                         our_number bigint NOT NULL,
                         barcode varchar(44) NOT NULL,
                         amount numeric(19,2) NOT NULL,
                         due_date date NOT NULL,
                         payable_until date NOT NULL,
                         fine_percent numeric(5,2) NOT NULL,
                         monthly_interest_percent numeric(5,2) NOT NULL,
                         payer_name varchar(120) NOT NULL,
                         payer_document varchar(14) NOT NULL,
                         description varchar(140),
                         status varchar(16) NOT NULL,
                         paid_amount numeric(19,2),
                         paid_at timestamp(6),
                         paid_via varchar(16),
                         created_at timestamp(6) NOT NULL,
                         updated_at timestamp(6) NOT NULL,
                         version bigint NOT NULL DEFAULT 0,
                         CONSTRAINT boletos_pkey PRIMARY KEY (id),
                         CONSTRAINT boletos_account_fkey FOREIGN KEY (beneficiary_account_id) REFERENCES accounts (id),
                         CONSTRAINT boletos_barcode_key UNIQUE (barcode),
                         CONSTRAINT boletos_our_number_key UNIQUE (our_number),
                         CONSTRAINT boletos_status_check CHECK (status IN ('OPEN', 'PAID', 'CANCELLED')),
                         CONSTRAINT boletos_amount_check CHECK (amount > 0),
                         -- teto legal: multa 2% (CDC), juros de mora 1% a.m.
                         CONSTRAINT boletos_fine_check CHECK (fine_percent BETWEEN 0 AND 2),
                         CONSTRAINT boletos_interest_check CHECK (monthly_interest_percent BETWEEN 0 AND 1),
                         CONSTRAINT boletos_paid_check CHECK (status <> 'PAID' OR (paid_amount IS NOT NULL AND paid_at IS NOT NULL))
);

CREATE INDEX boletos_beneficiary_idx ON boletos (beneficiary_account_id, created_at DESC);

-- Pagamentos de boleto feitos por clientes do Kage (boletos daqui ou de outros bancos).
CREATE TABLE boleto_payments (
                                 id uuid NOT NULL,
                                 payer_account_id uuid NOT NULL,
                                 barcode varchar(44) NOT NULL,
                                 bank_code varchar(3) NOT NULL,
                                 kind varchar(16) NOT NULL,
                                 boleto_id uuid,
                                 beneficiary_name varchar(255),
                                 due_date date,
                                 face_amount numeric(19,2) NOT NULL,
                                 fine numeric(19,2) NOT NULL,
                                 interest numeric(19,2) NOT NULL,
                                 paid_amount numeric(19,2) NOT NULL,
                                 paid_at timestamp(6) NOT NULL,
                                 CONSTRAINT boleto_payments_pkey PRIMARY KEY (id),
                                 CONSTRAINT boleto_payments_account_fkey FOREIGN KEY (payer_account_id) REFERENCES accounts (id),
                                 CONSTRAINT boleto_payments_boleto_fkey FOREIGN KEY (boleto_id) REFERENCES boletos (id),
                                 -- o mesmo código de barras não é pago duas vezes
                                 CONSTRAINT boleto_payments_barcode_key UNIQUE (barcode),
                                 CONSTRAINT boleto_payments_kind_check CHECK (kind IN ('INTERNAL', 'EXTERNAL')),
                                 CONSTRAINT boleto_payments_total_check CHECK (paid_amount = face_amount + fine + interest)
);

CREATE INDEX boleto_payments_payer_idx ON boleto_payments (payer_account_id, paid_at DESC);

ALTER TABLE account_entries DROP CONSTRAINT account_entries_type_check;
ALTER TABLE account_entries ADD CONSTRAINT account_entries_type_check
    CHECK (type IN ('DEPOSIT', 'WITHDRAWAL', 'PIX_RECEIVED', 'PIX_SENT', 'PIX_REFUND_RECEIVED', 'PIX_REFUND_SENT',
                    'INVOICE_PAYMENT', 'SAVINGS_DEPOSIT', 'SAVINGS_WITHDRAWAL', 'BOLETO_PAYMENT', 'BOLETO_RECEIVED'));
