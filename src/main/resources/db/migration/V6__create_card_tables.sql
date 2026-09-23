CREATE TABLE cards (
                        id uuid NOT NULL,
                        customer_id uuid NOT NULL,
                        account_id uuid NOT NULL,
                        credit_limit numeric(19,2) NOT NULL,
                        used_limit numeric(19,2) NOT NULL,
                        closing_day integer NOT NULL,
                        due_day integer NOT NULL,
                        status varchar(255) NOT NULL,
                        created_at timestamp(6) NOT NULL,
                        updated_at timestamp(6) NOT NULL,
                        version bigint NOT NULL DEFAULT 0,
                        CONSTRAINT cards_pkey PRIMARY KEY (id),
                        CONSTRAINT cards_status_check CHECK (status IN ('ACTIVE', 'BLOCKED', 'CANCELLED')),
                        CONSTRAINT cards_closing_day_check CHECK (closing_day BETWEEN 1 AND 28),
                        CONSTRAINT cards_due_day_check CHECK (due_day BETWEEN 1 AND 28)
);

CREATE TABLE invoices (
                          id uuid NOT NULL,
                          card_id uuid NOT NULL,
                          reference_month varchar(7) NOT NULL,
                          closing_date date NOT NULL,
                          due_date date NOT NULL,
                          status varchar(255) NOT NULL,
                          paid_at timestamp(6),
                          created_at timestamp(6) NOT NULL,
                          updated_at timestamp(6) NOT NULL,
                          version bigint NOT NULL DEFAULT 0,
                          CONSTRAINT invoices_pkey PRIMARY KEY (id),
                          CONSTRAINT invoices_card_id_reference_month_key UNIQUE (card_id, reference_month),
                          CONSTRAINT invoices_status_check CHECK (status IN ('OPEN', 'CLOSED', 'PAID'))
);

CREATE TABLE invoice_items (
                                id uuid NOT NULL,
                                invoice_id uuid NOT NULL,
                                purchase_id uuid NOT NULL,
                                description varchar(255) NOT NULL,
                                amount numeric(19,2) NOT NULL,
                                purchased_at timestamp(6) NOT NULL,
                                CONSTRAINT invoice_items_pkey PRIMARY KEY (id),
                                CONSTRAINT invoice_items_invoice_id_purchase_id_key UNIQUE (invoice_id, purchase_id)
);

ALTER TABLE invoices
    ADD CONSTRAINT invoices_card_id_fkey
        FOREIGN KEY (card_id) REFERENCES cards (id);

ALTER TABLE invoice_items
    ADD CONSTRAINT invoice_items_invoice_id_fkey
        FOREIGN KEY (invoice_id) REFERENCES invoices (id);
