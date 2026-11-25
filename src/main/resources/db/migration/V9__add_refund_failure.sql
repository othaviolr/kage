-- Estorno aprovado agora move dinheiro de forma assíncrona e pode falhar (ex.: quem recebeu já gastou).
ALTER TABLE pix_refunds DROP CONSTRAINT pix_refunds_refund_status_check;
ALTER TABLE pix_refunds ADD CONSTRAINT pix_refunds_refund_status_check
    CHECK (refund_status IN ('REQUESTED', 'APPROVED', 'REJECTED', 'COMPLETED', 'FAILED'));

ALTER TABLE pix_refunds ADD COLUMN failure_reason varchar(255);
