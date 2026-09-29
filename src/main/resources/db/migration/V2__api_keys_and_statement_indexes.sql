-- API keys are stored only as SHA-256 hashes; the plaintext key is never persisted.
CREATE TABLE api_keys (
    id                    UUID PRIMARY KEY,
    name                  TEXT        NOT NULL,
    key_hash              CHAR(64)    NOT NULL UNIQUE,
    rate_limit_per_minute INT         NOT NULL CHECK (rate_limit_per_minute > 0),
    active                BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- now() is the START of the transaction, so a transfer that waited on a row lock would get a timestamp
-- earlier than the transfer it queued behind. clock_timestamp() is taken at insert time, which is after the
-- lock is held, so per-account entry order and created_at order agree (statements sort by created_at, id).
ALTER TABLE entries ALTER COLUMN created_at SET DEFAULT clock_timestamp();

-- Serves the statement range scan and the running-balance window in index order, and lets balance
-- aggregates run as index-only scans (direction and amount are carried in the index).
CREATE INDEX idx_entries_account_created
    ON entries (account_id, created_at, id) INCLUDE (direction, amount);

-- Superseded by the index above (same leading column, and it also covers the balance aggregate).
DROP INDEX idx_entries_account_id;
