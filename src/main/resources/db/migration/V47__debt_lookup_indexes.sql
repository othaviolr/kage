-- "Minhas dívidas" busca cartões e empréstimos pelo cliente (todas as contas dele).
CREATE INDEX IF NOT EXISTS cards_customer_idx ON cards (customer_id);
CREATE INDEX IF NOT EXISTS loans_customer_idx ON loans (customer_id, contracted_at DESC);
