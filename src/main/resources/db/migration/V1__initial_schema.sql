-- Money is stored as BIGINT minor units (cents). No floating point anywhere.

CREATE TABLE accounts (
    id               UUID PRIMARY KEY,
    name             TEXT        NOT NULL CHECK (length(btrim(name)) > 0),
    currency         CHAR(3)     NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    type             TEXT        NOT NULL CHECK (type IN ('ASSET', 'LIABILITY')),
    -- How far the account's balance may go below zero (minor units, >= 0).
    overdraft_limit  BIGINT      NOT NULL DEFAULT 0 CHECK (overdraft_limit >= 0),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE transactions (
    id           UUID PRIMARY KEY,
    description  TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Append-only journal. A balance is always derived from these rows.
CREATE TABLE entries (
    id              BIGSERIAL PRIMARY KEY,
    transaction_id  UUID        NOT NULL REFERENCES transactions (id),
    account_id      UUID        NOT NULL REFERENCES accounts (id),
    direction       TEXT        NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount          BIGINT      NOT NULL CHECK (amount > 0),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_entries_account_id ON entries (account_id, id);
CREATE INDEX idx_entries_transaction_id ON entries (transaction_id);

-- Enforce append-only at the database level, not just in application code.
CREATE FUNCTION forbid_entry_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'entries are append-only: % is not allowed', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER entries_no_update_delete
    BEFORE UPDATE OR DELETE ON entries
    FOR EACH ROW EXECUTE FUNCTION forbid_entry_mutation();

CREATE TRIGGER entries_no_truncate
    BEFORE TRUNCATE ON entries
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_entry_mutation();

-- Idempotency records for POST /transfers. Keys are scoped per client so one client can
-- never replay (or collide with) another client's key.
CREATE TABLE idempotency_keys (
    client_id        TEXT        NOT NULL,
    idempotency_key  TEXT        NOT NULL,
    request_hash     TEXT        NOT NULL,
    response_status  INT,
    response_body    JSONB,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (client_id, idempotency_key)
);
