-- Reporting table populated by the nightly rollup job. One row per account per UTC day on which the account
-- had activity; closing_balance is in the account's normal-balance terms (minor units) and includes
-- everything up to the end of that day. Days without activity have no row (the balance carries forward).
-- This table is derived data: it can be rebuilt from `entries` at any time and is never read by the
-- posting path.
CREATE TABLE daily_balances (
    account_id       UUID        NOT NULL REFERENCES accounts (id),
    balance_date     DATE        NOT NULL,
    debit_total      BIGINT      NOT NULL,
    credit_total     BIGINT      NOT NULL,
    entry_count      INT         NOT NULL,
    closing_balance  BIGINT      NOT NULL,
    computed_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, balance_date)
);

CREATE INDEX idx_daily_balances_date ON daily_balances (balance_date);
