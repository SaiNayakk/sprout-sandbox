# sprout-sandbox

**The public demo**: anyone can explore Sprout as one of fifteen fictional customers with months of real
history, without signing up. (Signing up for real works too.)

- **Who to explore as.** Visitors choose: a woman, a man, someone non-binary or of another gender, or
  no preference. Five fictional people in each group, from across India, each with their own pronouns,
  city, story and way of investing. Every way of investing (steady plans, round-ups, saving for a goal,
  just starting, exploring) appears once in every group, so no group is given a stereotype.
- **A visitor gets whoever in their group was explored least recently**, with an ordinary signed-in
  session as them. No password is ever stored: each sign-in sets a new long random one through
  identity's demo users, under the person's row lock, and uses it at once.
- **History is real.** The sandbox's market runs fast, and every trading session each person lives
  their way through the same APIs as any customer: paid monthly (by a fictional payroll), money added
  when cash runs low, UPI spends at the demo merchants rounded up and swept, pots topped up, shares
  bought and now and then one sold. Their streaks, statements and reconciliation are as real as anyone's.
- **Set up like a customer.** Each person is set up step by step through the real flows: a demo
  sign-in, a bank account with a UPI PIN, a Sprout account (KYC), a first deposit approved in the bank,
  then AutoPay and round-ups, a plan or a pot, readiness, one of three mixed squads, and a friend's
  referral code. Every step is safe to repeat.

## Part of Sprout

[Sprout](https://sainayakk.github.io/sprout-platform/) is a simulated brokerage built from scratch as
separate services, each with its own repository and contract. Architecture, environments and test
evidence live in [sprout-platform](https://github.com/SaiNayakk/sprout-platform); this service's API is
[`sandbox-v1.yaml`](https://github.com/SaiNayakk/sprout-contracts/blob/main/src/main/resources/sprout/contracts/openapi/sandbox-v1.yaml)
in sprout-contracts. It runs inside the **edge** host, and only in the sandbox.

`./mvnw verify` runs the tests on a real Postgres against one stand-in for the rest of Sprout, which
records every call: everyone set up through the same steps once, visitors rotated through their group,
and each session lived once by each person in their own way.

## License

MIT
