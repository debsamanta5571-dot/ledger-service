-- Every account belongs to the caller that created it: an API key (its id) or an identity-service user
-- ("user:<subject>"). Callers can only see and operate their own accounts; they may still pay INTO anyone's.
ALTER TABLE accounts ADD COLUMN owner_id TEXT;

-- Accounts created before ownership existed were all made through the bootstrap API key, so they go to it.
-- (A fresh database has no rows here, and no key yet: Flyway runs before the key is registered.)
UPDATE accounts SET owner_id = COALESCE(
    (SELECT id::text FROM api_keys WHERE name = 'bootstrap' AND active ORDER BY created_at LIMIT 1),
    'unowned');

ALTER TABLE accounts ALTER COLUMN owner_id SET NOT NULL;

-- Listings are now per owner; this replaces V5's global "open accounts, newest first" index.
DROP INDEX idx_accounts_open_created;
CREATE INDEX idx_accounts_owner_created ON accounts (owner_id, created_at DESC, id);
