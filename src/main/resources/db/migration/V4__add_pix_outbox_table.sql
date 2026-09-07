CREATE TABLE pix_outbox (
                            id uuid NOT NULL,
                            payload text NOT NULL,
                            exchange varchar(255) NOT NULL,
                            routing_key varchar(255) NOT NULL,
                            created_at timestamp(6) NOT NULL,
                            published_at timestamp(6),
                            CONSTRAINT pix_outbox_pkey PRIMARY KEY (id)
);