-- Débito automático da fatura no vencimento: opção do cliente, desligada por padrão.
ALTER TABLE cards ADD COLUMN auto_debit boolean NOT NULL DEFAULT false;

-- o job diário procura faturas abertas que passaram do corte e fechadas que venceram
CREATE INDEX invoices_status_closing_date_idx ON invoices (status, closing_date);
CREATE INDEX invoices_status_due_date_idx ON invoices (status, due_date);
