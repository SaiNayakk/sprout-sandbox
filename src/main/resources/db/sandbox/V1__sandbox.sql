-- The sandbox's fictional people: how far each is set up, and what was done for them when, so every
-- step and every session's actions happen once.

CREATE TABLE personas (
    id                  text PRIMARY KEY,
    user_id             uuid,
    step                int NOT NULL DEFAULT 0,             -- the next set-up step to do
    ready               boolean NOT NULL DEFAULT false,
    last_served_at      timestamptz,                        -- when a visitor was last given this person
    served              int NOT NULL DEFAULT 0,
    last_session        date,                               -- the last market session they lived
    sessions_lived      int NOT NULL DEFAULT 0,
    last_salary_month   text,                               -- YYYY-MM of the last salary paid
    bank_vpa            text
);

-- Things shared between people (the squad's invite code).
CREATE TABLE facts (
    name  text PRIMARY KEY,
    value text NOT NULL
);
