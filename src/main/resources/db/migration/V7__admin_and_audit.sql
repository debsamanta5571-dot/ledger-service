-- Admins (scope ledger:admin) can act on any account, so every transaction now records WHO initiated it. An admin
-- moving money out of someone's account is then visible on that account's statement. Rows written before this
-- migration have no initiator (NULL).
ALTER TABLE transactions ADD COLUMN created_by TEXT;
ALTER TABLE transactions ADD COLUMN created_by_name TEXT;

-- A readable owner label (the user's name, or the API key's name) captured when the account is created, so an
-- admin's account list shows people rather than opaque ids. Informational only: ownership is owner_id.
ALTER TABLE accounts ADD COLUMN owner_name TEXT;
UPDATE accounts SET owner_name = k.name FROM api_keys k WHERE accounts.owner_id = k.id::text AND accounts.owner_name IS NULL;
