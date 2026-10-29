# sprout-sandbox

**The public demo**: anyone can try Sprout with a demo account of their own that already has a month of real
history, without signing up. (Signing up for real works too.)

- **One account per visitor, while they are there.** A visitor types a name and is signed in to a demo account
  nobody else is using. The web app sends a heartbeat every 30 seconds; the account is closed when the
  heartbeats stop (the tab was closed, `gone-after`, 2 minutes), when the visitor has done nothing for
  `idle-after` (15 minutes), when they leave, or at the latest after `keep-for` (2 hours). No password is ever
  stored: each sign-in sets a new long random one through identity's demo users and uses it at once, and the
  heartbeat key is kept only as its SHA-256.
- **A pool that grows with the visitors.** `warm` accounts (10) are always waiting; more start as visitors take
  them, up to `most` open at once (50). Beyond that a visitor is told they're next in line and the web app asks
  again.
- **The same history for everyone.** Each account lives `history` sessions (20, about a month) of the sandbox's
  fast market before a visitor gets it, through the same APIs as any customer: paid monthly by a fictional
  payroll, UPI spends at the demo shops rounded up into a pot, a plan, the odd share bought or sold. What it
  does in each session is decided by its age alone, so every account has the same history. Then it stops: the
  visitor drives.
- **Set up like a customer.** Each account is set up step by step through the real flows: a demo sign-in, a
  bank account with a UPI PIN, a Sprout account (KYC), a first deposit approved in the bank, then AutoPay and
  round-ups, a plan and a pot, readiness, and the demo squad. Every step is safe to repeat.

## Part of Sprout

[Sprout](https://sainayakk.github.io/sprout-platform/) is a simulated brokerage built from scratch as
separate services, each with its own repository and contract. Architecture, environments and test
evidence live in [sprout-platform](https://github.com/SaiNayakk/sprout-platform); this service's API is
[`sandbox-v1.yaml`](https://github.com/SaiNayakk/sprout-contracts/blob/main/src/main/resources/sprout/contracts/openapi/sandbox-v1.yaml)
in sprout-contracts. It runs inside the **edge** host, and only in the sandbox.

`./mvnw verify` runs the tests on a real Postgres against one stand-in for the rest of Sprout, which
records every call: every account set up through the same steps once, each session lived once, a visitor's
account kept while they're there and closed when they go idle, close the tab or leave, and visitors told to
wait their turn when every account is in use.

## License

MIT
