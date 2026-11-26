-- Contestação procedente devolve os juros e encargos que a compra gerou enquanto estava cobrada
-- (rotativo e parcelamento da fatura, na proporção da compra). Entra como crédito na fatura atual.
ALTER TABLE card_disputes ADD COLUMN interest_refunded NUMERIC(19, 2) NOT NULL DEFAULT 0
    CHECK (interest_refunded >= 0);

ALTER TABLE invoice_items DROP CONSTRAINT invoice_items_kind_check;
ALTER TABLE invoice_items ADD CONSTRAINT invoice_items_kind_check
    CHECK (kind IN ('PURCHASE', 'PREVIOUS_BALANCE', 'REVOLVING_INTEREST', 'LATE_FINE', 'LATE_INTEREST',
                    'INVOICE_INSTALLMENT', 'ANTICIPATION', 'DISPUTE_CREDIT', 'DISPUTE_REVERSAL', 'CREDIT_BALANCE',
                    'DISPUTE_INTEREST_REFUND'));
