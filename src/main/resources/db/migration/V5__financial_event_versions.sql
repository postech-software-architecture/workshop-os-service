ALTER TABLE saga_details ADD COLUMN financial_version BIGINT NOT NULL DEFAULT 0;
UPDATE foundation_state SET schema_version=5;
