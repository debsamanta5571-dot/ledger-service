-- "Removing" an account closes it. Rows are never deleted: its entries are append-only history, and deleting the
-- account would either break their foreign keys or erase the audit trail. A closed account keeps its statement,
-- but it is hidden from listings and can no longer send or receive transfers.
ALTER TABLE accounts ADD COLUMN closed_at TIMESTAMPTZ;

-- Listings show open accounts only, newest first.
CREATE INDEX idx_accounts_open_created ON accounts (created_at DESC, id) WHERE closed_at IS NULL;
