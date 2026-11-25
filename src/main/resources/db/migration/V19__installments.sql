-- Compras parceladas e parcelamento de fatura.

ALTER TABLE invoice_items ADD COLUMN installment_number integer;
ALTER TABLE invoice_items ADD COLUMN installment_count integer;
-- tudo que existia antes era compra à vista
UPDATE invoice_items SET installment_number = 1, installment_count = 1 WHERE kind = 'PURCHASE';
ALTER TABLE invoice_items ADD CONSTRAINT invoice_items_installment_check CHECK (
    (installment_number IS NULL AND installment_count IS NULL)
    OR (installment_number BETWEEN 1 AND installment_count AND installment_count BETWEEN 1 AND 12));

ALTER TABLE invoice_items DROP CONSTRAINT invoice_items_kind_check;
ALTER TABLE invoice_items ADD CONSTRAINT invoice_items_kind_check
    CHECK (kind IN ('PURCHASE', 'PREVIOUS_BALANCE', 'REVOLVING_INTEREST', 'LATE_FINE', 'LATE_INTEREST', 'INVOICE_INSTALLMENT'));

ALTER TABLE invoices DROP CONSTRAINT invoices_status_check;
ALTER TABLE invoices ADD CONSTRAINT invoices_status_check
    CHECK (status IN ('OPEN', 'CLOSED', 'PAID', 'ROLLED_OVER', 'FINANCED'));

-- idempotência da compra: a mesma compra parcelada aparece em várias faturas; quem responde pelo
-- "já registrada?" é a 1ª parcela
CREATE INDEX invoice_items_purchase_first_idx ON invoice_items (purchase_id) WHERE installment_number = 1;
