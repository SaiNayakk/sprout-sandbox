-- Demo accounts of the visitors' own (sandbox v3). A few are kept warm, each living the fast market's
-- sessions the way a customer does; a visitor is given the one with the most history, named as they choose,
-- until ends_at, after which it is retired (and, later, swept away). The fictional personas of v1 stay in
-- `personas`, no longer living or given out.

CREATE SEQUENCE demo_account_numbers;

CREATE TABLE demo_accounts (
    id                  uuid PRIMARY KEY,
    number              bigint NOT NULL UNIQUE,           -- its sign-in, legal name and PAN are made from this
    created_at          timestamptz NOT NULL,
    user_id             uuid,
    step                int NOT NULL DEFAULT 0,           -- the next set-up step to do
    ready               boolean NOT NULL DEFAULT false,
    claimed_at          timestamptz,                      -- when a visitor was given it
    claimed_name        text,                             -- what they asked to be called
    ends_at             timestamptz,                      -- when it stops being theirs
    retired_at          timestamptz,                      -- when it stopped living and was closed to sign-in
    last_session        date,                             -- the last market session it lived
    sessions_lived      int NOT NULL DEFAULT 0,
    last_salary_month   text,                             -- YYYY-MM of the last salary paid
    bank_vpa            text
);

-- the warm accounts a visitor can be given, most history first
CREATE INDEX demo_accounts_available ON demo_accounts (sessions_lived DESC, number)
    WHERE ready AND claimed_at IS NULL AND retired_at IS NULL;
