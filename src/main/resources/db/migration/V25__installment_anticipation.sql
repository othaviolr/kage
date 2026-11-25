-- Antecipação de parcelas: as parcelas futuras saem das faturas delas e entram na fatura atual
-- numa linha só, já com o desconto dos juros (CDC, art. 52, §2º).
ALTER TABLE invoice_items DROP CONSTRAINT invoice_items_kind_check;
ALTER TABLE invoice_items ADD CONSTRAINT invoice_items_kind_check
    CHECK (kind IN ('PURCHASE', 'PREVIOUS_BALANCE', 'REVOLVING_INTEREST', 'LATE_FINE', 'LATE_INTEREST',
                    'INVOICE_INSTALLMENT', 'ANTICIPATION'));
