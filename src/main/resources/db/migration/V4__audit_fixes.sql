-- Limits found missing in the audit. The API validates the same bounds; these make them hold for any writer.

-- Bring rows written before these limits existed into range first, or adding the constraints would fail.
-- (Accounts are reference data; only `entries` is append-only.)
UPDATE accounts SET name = left(name, 200) WHERE length(name) > 200;
UPDATE accounts SET overdraft_limit = 999999999999999 WHERE overdraft_limit > 999999999999999;

-- Account names were unbounded (a 10,000-character name was accepted).
ALTER TABLE accounts ADD CONSTRAINT accounts_name_length CHECK (length(name) <= 200);

-- An overdraft limit near Long.MAX_VALUE made "balance + limit" overflow in the overdraft check.
-- 999,999,999,999,999 minor units matches the largest allowed transfer amount.
ALTER TABLE accounts ADD CONSTRAINT accounts_overdraft_max CHECK (overdraft_limit <= 999999999999999);

-- now() is the transaction START, so a transfer that waited on the account lock got a header timestamp earlier
-- than its own entries (which already use clock_timestamp(), see V2). Use insert time for both.
ALTER TABLE transactions ALTER COLUMN created_at SET DEFAULT clock_timestamp();
