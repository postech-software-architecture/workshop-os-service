CREATE TABLE os_workflow (
 os_id UUID PRIMARY KEY REFERENCES ordens_servico(id), attempt_id UUID NOT NULL, saga_id UUID NOT NULL,
 work_id UUID UNIQUE NOT NULL, correlation_id UUID NOT NULL, request_key UUID UNIQUE NOT NULL, request_body TEXT NOT NULL,
 stage TEXT NOT NULL, budget_id UUID, budget_version INTEGER NOT NULL DEFAULT 1,
 work_version BIGINT NOT NULL DEFAULT 0, version BIGINT NOT NULL DEFAULT 1, diagnostic TEXT,
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE TABLE composition_snapshot (
 item_id UUID PRIMARY KEY, os_id UUID NOT NULL REFERENCES ordens_servico(id), budget_version INTEGER NOT NULL,
 snapshot TEXT NOT NULL);
CREATE INDEX composition_by_order ON composition_snapshot(os_id,budget_version);
CREATE TABLE os_item_requests (
 os_id UUID NOT NULL REFERENCES ordens_servico(id), request_key UUID NOT NULL, request_body TEXT NOT NULL,
 item_id UUID NOT NULL, PRIMARY KEY(os_id,request_key));
CREATE TABLE stock_reservations (
 reservation_id UUID PRIMARY KEY REFERENCES movimentacoes_estoque(id), os_id UUID NOT NULL REFERENCES ordens_servico(id),
 attempt_id UUID NOT NULL, budget_version INTEGER NOT NULL, state TEXT NOT NULL CHECK(state IN ('RESERVADO','CONSUMIDO','LIBERADO')),
 result_movement_id UUID UNIQUE REFERENCES movimentacoes_estoque(id));
UPDATE foundation_state SET schema_version=3;
-- Fixture tables remain only for Flyway compatibility; the synthetic account is disabled.
UPDATE foundation_users SET active=false;
