CREATE TABLE account_processed_events (
                                          event_id uuid NOT NULL,
                                          processed_at timestamp(6) NOT NULL,
                                          CONSTRAINT account_processed_events_pkey PRIMARY KEY (event_id)
);