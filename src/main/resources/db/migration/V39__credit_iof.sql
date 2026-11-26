-- IOF das operações de crédito (Decreto 6.306/2007, art. 7º): rotativo e parcelamento da fatura ganham
-- uma linha própria de IOF. É imposto — fora do teto de juros e encargos da Lei 14.690/2023.
ALTER TABLE invoice_items DROP CONSTRAINT invoice_items_kind_check;
ALTER TABLE invoice_items ADD CONSTRAINT invoice_items_kind_check
    CHECK (kind IN ('PURCHASE', 'PREVIOUS_BALANCE', 'REVOLVING_INTEREST', 'LATE_FINE', 'LATE_INTEREST',
                    'INVOICE_INSTALLMENT', 'ANTICIPATION', 'DISPUTE_CREDIT', 'DISPUTE_REVERSAL', 'CREDIT_BALANCE',
                    'DISPUTE_INTEREST_REFUND', 'IOF'));
