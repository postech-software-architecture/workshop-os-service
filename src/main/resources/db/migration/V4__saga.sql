ALTER TABLE os_workflow ADD COLUMN last_command_type TEXT;
ALTER TABLE os_workflow ADD COLUMN last_command_payload TEXT;
ALTER TABLE os_workflow ADD COLUMN next_retry_at TIMESTAMPTZ;
ALTER TABLE os_workflow ADD COLUMN deadline TIMESTAMPTZ;
ALTER TABLE os_workflow ADD COLUMN retry_count INTEGER NOT NULL DEFAULT 0;
CREATE TABLE saga_details (
 os_id UUID PRIMARY KEY REFERENCES ordens_servico(id), payment_id UUID UNIQUE,
 total_centavos BIGINT NOT NULL DEFAULT 0, financial_state TEXT NOT NULL DEFAULT 'NONE',
 work_cancelled BOOLEAN NOT NULL DEFAULT false, financial_done BOOLEAN NOT NULL DEFAULT false,
 stock_reversed BOOLEAN NOT NULL DEFAULT false, compensation_reason TEXT);
CREATE TABLE saga_audit (
 id UUID PRIMARY KEY, os_id UUID NOT NULL REFERENCES ordens_servico(id), event_type TEXT NOT NULL,
 message_id UUID, detail TEXT, occurred_at TIMESTAMPTZ NOT NULL DEFAULT now());
ALTER TABLE stock_reservations DROP CONSTRAINT stock_reservations_state_check;
ALTER TABLE stock_reservations ADD CONSTRAINT stock_reservations_state_check CHECK(state IN ('RESERVADO','CONSUMIDO','LIBERADO','REVERTIDO'));
ALTER TABLE stock_reservations ADD COLUMN reversal_movement_id UUID UNIQUE REFERENCES movimentacoes_estoque(id);
UPDATE foundation_state SET schema_version=4;
