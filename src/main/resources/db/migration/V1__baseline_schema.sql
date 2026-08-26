CREATE TABLE accounts (
                          id uuid NOT NULL,
                          account_digit varchar(1) NOT NULL,
                          account_number varchar(5) NOT NULL,
                          available_balance numeric(19,2) NOT NULL,
                          balance numeric(19,2) NOT NULL,
                          branch varchar(4) NOT NULL,
                          closed_at timestamp(6),
                          customer_id uuid NOT NULL,
                          daily_transfer_limit numeric(19,2) NOT NULL,
                          monthly_transfer_limit numeric(19,2) NOT NULL,
                          opened_at timestamp(6) NOT NULL,
                          pix_daily_limit numeric(19,2) NOT NULL,
                          pix_night_limit numeric(19,2) NOT NULL,
                          status varchar(255) NOT NULL,
                          type varchar(255) NOT NULL,
                          updated_at timestamp(6) NOT NULL,
                          daily_withdrawal_limit numeric(38,2) NOT NULL,
                          CONSTRAINT accounts_pkey PRIMARY KEY (id),
                          CONSTRAINT accounts_account_number_key UNIQUE (account_number),
                          CONSTRAINT accounts_status_check CHECK (status IN ('ACTIVE', 'BLOCKED', 'CLOSED')),
                          CONSTRAINT accounts_type_check CHECK (type IN ('CHECKING', 'SAVINGS'))
);

CREATE TABLE customers (
                           id uuid NOT NULL,
                           birth_date date NOT NULL,
                           city varchar(255) NOT NULL,
                           complement varchar(255),
                           cpf varchar(255) NOT NULL,
                           created_at timestamp(6) NOT NULL,
                           email varchar(255) NOT NULL,
                           full_name varchar(255) NOT NULL,
                           kyc_status varchar(255) NOT NULL,
                           number varchar(255) NOT NULL,
                           phone varchar(255) NOT NULL,
                           state varchar(255) NOT NULL,
                           status varchar(255) NOT NULL,
                           street varchar(255) NOT NULL,
                           updated_at timestamp(6) NOT NULL,
                           zip_code varchar(255) NOT NULL,
                           CONSTRAINT customers_pkey PRIMARY KEY (id),
                           CONSTRAINT customers_cpf_key UNIQUE (cpf),
                           CONSTRAINT customers_email_key UNIQUE (email),
                           CONSTRAINT customers_kyc_status_check CHECK (kyc_status IN ('PENDING', 'APPROVED', 'REJECTED')),
                           CONSTRAINT customers_status_check CHECK (status IN ('ACTIVE', 'INACTIVE', 'BLOCKED'))
);

CREATE TABLE pix_keys (
                          pix_key_id uuid NOT NULL,
                          account_id uuid NOT NULL,
                          created_at timestamp(6) NOT NULL,
                          deleted_at timestamp(6),
                          key_status varchar(255) NOT NULL,
                          key_type varchar(255) NOT NULL,
                          key_value varchar(255) NOT NULL,
                          CONSTRAINT pix_keys_pkey PRIMARY KEY (pix_key_id),
                          CONSTRAINT pix_keys_key_value_key UNIQUE (key_value),
                          CONSTRAINT pix_keys_key_status_check CHECK (key_status IN ('ACTIVE', 'DELETED', 'PORTABILITY_REQUESTED')),
                          CONSTRAINT pix_keys_key_type_check CHECK (key_type IN ('CPF', 'EMAIL', 'PHONE', 'RANDOM'))
);

CREATE TABLE pix_refunds (
                             refund_id uuid NOT NULL,
                             original_transaction_id uuid NOT NULL,
                             processed_at timestamp(6),
                             processed_by varchar(255),
                             reason varchar(255) NOT NULL,
                             refund_amount numeric(19,2) NOT NULL,
                             refund_status varchar(255) NOT NULL,
                             requested_at timestamp(6) NOT NULL,
                             CONSTRAINT pix_refunds_pkey PRIMARY KEY (refund_id),
                             CONSTRAINT pix_refunds_refund_status_check CHECK (refund_status IN ('REQUESTED', 'APPROVED', 'REJECTED', 'COMPLETED'))
);

CREATE TABLE pix_transactions (
                                  transaction_id uuid NOT NULL,
                                  amount numeric(19,2) NOT NULL,
                                  completed_at timestamp(6),
                                  created_at timestamp(6) NOT NULL,
                                  description varchar(255),
                                  e2e_id varchar(255) NOT NULL,
                                  failure_reason varchar(255),
                                  idempotency_key uuid NOT NULL,
                                  processed_at timestamp(6),
                                  scheduled_date timestamp(6),
                                  source_account_id uuid NOT NULL,
                                  status varchar(255) NOT NULL,
                                  target_account_id uuid,
                                  target_pix_key varchar(255) NOT NULL,
                                  transaction_type varchar(255) NOT NULL,
                                  CONSTRAINT pix_transactions_pkey PRIMARY KEY (transaction_id),
                                  CONSTRAINT pix_transactions_idempotency_key_key UNIQUE (idempotency_key),
                                  CONSTRAINT pix_transactions_e2e_id_key UNIQUE (e2e_id),
                                  CONSTRAINT pix_transactions_status_check CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'CANCELLED')),
                                  CONSTRAINT pix_transactions_transaction_type_check CHECK (transaction_type IN ('IMMEDIATE', 'SCHEDULED'))
);

ALTER TABLE pix_refunds
    ADD CONSTRAINT pix_refunds_original_transaction_fkey
        FOREIGN KEY (original_transaction_id) REFERENCES pix_transactions (transaction_id);