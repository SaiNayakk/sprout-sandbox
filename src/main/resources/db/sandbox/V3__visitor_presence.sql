-- A visitor keeps their demo account while they are there (sandbox v3.1): the web app sends a heartbeat, saying
-- whether they did anything, and the account is retired once the heartbeats stop or the visitor goes idle.
-- The visitor's demo session key is stored only as its SHA-256.

ALTER TABLE demo_accounts
    ADD COLUMN session_key_sha256 text UNIQUE,
    ADD COLUMN seen_at            timestamptz,     -- the last heartbeat
    ADD COLUMN active_at          timestamptz;     -- the last heartbeat that said the visitor did something
