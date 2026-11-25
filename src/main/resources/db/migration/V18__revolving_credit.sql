-- Pagamento parcial, rotativo e encargos por atraso.

-- quanto já foi pago de cada fatura (parcial ou total)
ALTER TABLE invoices ADD COLUMN paid_amount numeric(19,2) NOT NULL DEFAULT 0;
-- faturas pagas antes desta migração foram pagas pelo total
UPDATE invoices i SET paid_amount = (SELECT COALESCE(SUM(it.amount), 0) FROM invoice_items it WHERE it.invoice_id = i.id)
WHERE i.status = 'PAID';

ALTER TABLE invoices DROP CONSTRAINT invoices_status_check;
ALTER TABLE invoices ADD CONSTRAINT invoices_status_check CHECK (status IN ('OPEN', 'CLOSED', 'PAID', 'ROLLED_OVER'));
ALTER TABLE invoices ADD CONSTRAINT invoices_paid_amount_check CHECK (paid_amount >= 0);

-- cada linha da fatura diz o que é: compra ou encargo da virada
ALTER TABLE invoice_items ADD COLUMN kind varchar(32) NOT NULL DEFAULT 'PURCHASE';
ALTER TABLE invoice_items ADD CONSTRAINT invoice_items_kind_check
    CHECK (kind IN ('PURCHASE', 'PREVIOUS_BALANCE', 'REVOLVING_INTEREST', 'LATE_FINE', 'LATE_INTEREST'));

-- teto da Lei 14.690/2023: encargos do rotativo não passam de 100% da dívida original
ALTER TABLE cards ADD COLUMN revolving_principal numeric(19,2) NOT NULL DEFAULT 0;
ALTER TABLE cards ADD COLUMN revolving_charges numeric(19,2) NOT NULL DEFAULT 0;
ALTER TABLE cards ADD CONSTRAINT cards_revolving_cap_check CHECK (revolving_charges <= revolving_principal);

-- o ciclo procura faturas fechadas com saldo cujo próximo fechamento já passou
CREATE INDEX invoices_status_closing_date_card_idx ON invoices (card_id, status);
