CREATE TABLE outbox (
 id UUID PRIMARY KEY, body TEXT NOT NULL, exchange_name TEXT NOT NULL, routing_key TEXT NOT NULL,
 traceparent TEXT, published BOOLEAN NOT NULL DEFAULT FALSE, attempts INTEGER NOT NULL DEFAULT 0,
 next_at TIMESTAMPTZ NOT NULL DEFAULT now(), lease_until TIMESTAMPTZ, lease_token UUID);
CREATE INDEX outbox_pending ON outbox(next_at) WHERE published=false;
CREATE TABLE inbox (message_id UUID PRIMARY KEY, processed_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE TABLE foundation_state (id INTEGER PRIMARY KEY CHECK(id=1), schema_version INTEGER NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now());
INSERT INTO foundation_state(id,schema_version) VALUES(1,1);

CREATE TABLE foundation_orders (
 id UUID PRIMARY KEY, attempt_id UUID NOT NULL, saga_id UUID NOT NULL, work_id UUID NOT NULL,
 request_key UUID UNIQUE NOT NULL, request_text VARCHAR(500) NOT NULL, status TEXT NOT NULL,
 correlation_id UUID NOT NULL, version BIGINT NOT NULL DEFAULT 1);
CREATE TABLE foundation_users(id UUID PRIMARY KEY, username TEXT NOT NULL, roles TEXT NOT NULL, active BOOLEAN NOT NULL);
INSERT INTO foundation_users VALUES('11111111-1111-4111-8111-111111111111','demo-admin','ADMINISTRADOR,ATENDENTE,MECANICO',true);
