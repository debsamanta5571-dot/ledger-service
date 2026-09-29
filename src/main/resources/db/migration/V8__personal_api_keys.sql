-- Personal API keys: a key that acts AS a person (owner_id is a Caller id such as "user:<subject>"), so scripts can
-- use that person's accounts without a browser sign-in. Existing keys (the bootstrap key) have no owner and keep
-- acting as themselves (their own id), with the globally configured scopes.
ALTER TABLE api_keys ADD COLUMN owner_id TEXT;
ALTER TABLE api_keys ADD COLUMN owner_name TEXT;
-- Space-separated scopes this key carries; NULL = the configured default (ledger.auth.api-key-scopes).
ALTER TABLE api_keys ADD COLUMN scopes TEXT;
-- Who created it (a Caller id), for the audit trail.
ALTER TABLE api_keys ADD COLUMN created_by TEXT;

CREATE INDEX idx_api_keys_owner ON api_keys (owner_id) WHERE owner_id IS NOT NULL;
