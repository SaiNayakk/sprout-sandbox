package app.sprout.sandbox.domain;

import app.sprout.sandbox.domain.Upstreams.Reply;
import app.sprout.sandbox.domain.Upstreams.Unreachable;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Keeps the pool of demo accounts: retires those whose visitor's time is up, starts new ones so a few are
 * always warming for the next visitors, and sets each new one up the way a real customer sets up, through
 * the same APIs: a sign-in, a bank account with a UPI PIN, a Sprout account (KYC), a first deposit
 * approved in the bank, AutoPay for round-ups, a plan, a pot, readiness, and the demo squad. One step per
 * account per round; every step is safe to repeat, and one that can't be done yet is tried next round.
 */
@Component
public class Setup {

    private static final Logger log = LoggerFactory.getLogger(Setup.class);

    static final String[] STEPS = {"IDENTITY", "BANK", "ACCOUNT", "DEPOSIT", "AUTOPAY", "PLAN", "POT", "READINESS", "SQUAD"};
    static final String SQUAD = "Sprout Demo Savers";

    private final JdbcClient db;
    private final Upstreams up;
    private final Accounts accounts;
    private final Clock clock;

    public Setup(JdbcClient db, Upstreams up, Accounts accounts, Clock clock) {
        this.db = db;
        this.up = up;
        this.accounts = accounts;
        this.clock = clock;
    }

    public void round() {
        retire();
        warm();
        List<DemoAccount> setting = db.sql("SELECT id, number FROM demo_accounts WHERE NOT ready AND retired_at IS NULL ORDER BY number")
                .query((rs, n) -> new DemoAccount(rs.getObject(1, UUID.class), rs.getLong(2))).list();
        for (DemoAccount a : setting) {
            int step = db.sql("SELECT step FROM demo_accounts WHERE id = ?").param(a.id()).query(Integer.class).single();
            try {
                if (step(a, STEPS[step])) {
                    boolean ready = step + 1 >= STEPS.length;
                    db.sql("UPDATE demo_accounts SET step = ?, ready = ? WHERE id = ?").params(step + 1, ready, a.id()).update();
                    if (ready) {
                        log.info("Demo account {} is set up and warming", a.number());
                    }
                }
            } catch (Unreachable e) {
                log.info("Demo account {}: {} not done yet ({})", a.number(), STEPS[step], e.getMessage());
            }
        }
    }

    /** Accounts whose visitor's time is up stop living and can't be signed in to again. */
    void retire() {
        List<DemoAccount> due = db.sql("SELECT id, number FROM demo_accounts WHERE ends_at <= ? AND retired_at IS NULL")
                .param(Timestamp.from(clock.instant()))
                .query((rs, n) -> new DemoAccount(rs.getObject(1, UUID.class), rs.getLong(2))).list();
        for (DemoAccount a : due) {
            try {
                accounts.close(a);
                db.sql("UPDATE demo_accounts SET retired_at = ? WHERE id = ?").params(Timestamp.from(clock.instant()), a.id()).update();
                log.info("Demo account {} retired: its visitor's time is up", a.number());
            } catch (Unreachable e) {
                log.info("Demo account {} couldn't be closed yet ({})", a.number(), e.getMessage());
            }
        }
    }

    /** Starts new accounts until {@code warm} are waiting (set up or still setting up) for visitors. */
    void warm() {
        int waiting = db.sql("SELECT count(*) FROM demo_accounts WHERE claimed_at IS NULL AND retired_at IS NULL").query(Integer.class).single();
        for (int i = waiting; i < up.props.warm(); i++) {
            long number = db.sql("SELECT nextval('demo_account_numbers')").query(Long.class).single();
            db.sql("INSERT INTO demo_accounts (id, number, created_at) VALUES (?, ?, ?)")
                    .params(UUID.randomUUID(), number, Timestamp.from(clock.instant())).update();
            log.info("Demo account {} started, for a future visitor", number);
        }
    }

    /** Does one step; true once it's done. */
    boolean step(DemoAccount a, String step) {
        UUID user = step.equals("IDENTITY") ? null : accounts.user(a);
        return switch (step) {
            case "IDENTITY" -> {
                accounts.signIn(a, a.legalName());   // creates the demo user (and proves it signs in)
                yield true;
            }
            case "BANK" -> {
                Reply r = up.as(user, "POST", up.props.bankUrl() + "/v1/accounts", Map.of("holderName", a.legalName(), "upiPin", up.props.upiPin()), null);
                if (!r.ok() && !r.code().equals("ACCOUNT_EXISTS")) {
                    throw new Unreachable("the bank refused the account: " + r.code());
                }
                String vpa = up.as(user, "GET", up.props.bankUrl() + "/v1/accounts/me", null, null).body().path("vpa").asText();
                db.sql("UPDATE demo_accounts SET bank_vpa = ? WHERE id = ?").params(vpa, a.id()).update();
                yield true;
            }
            case "ACCOUNT" -> {
                Reply r = up.as(user, "POST", up.props.accountsUrl() + "/v1/accounts", Map.of("legalName", a.legalName(),
                        "dateOfBirth", DemoAccount.DATE_OF_BIRTH, "pan", a.pan(), "bankVpa", accounts.vpa(a)), null);
                if (!r.ok() && !r.code().equals("ACCOUNT_EXISTS")) {
                    throw new Unreachable("accounts refused: " + r.code());
                }
                yield true;
            }
            case "DEPOSIT" -> accounts.addMoney(a, 25000, "sandbox:demo-" + a.number() + ":first-deposit");
            case "AUTOPAY" -> {
                up.as(user, "POST", up.props.paymentsUrl() + "/v1/mandates", Map.of("maxAmount", "1000"), "sandbox:demo-" + a.number() + ":autopay");
                approvePendingMandates(user);
                yield "ACTIVE".equals(up.as(user, "GET", up.props.paymentsUrl() + "/v1/mandates/me", null, null).body().path("status").asText());
            }
            case "PLAN" -> up.as(user, "POST", up.props.plansUrl() + "/v1/plans", Map.of("symbol", DemoAccount.PLAN_SYMBOL, "amount",
                    String.valueOf(DemoAccount.PLAN_AMOUNT), "dayOfMonth", 5, "startNow", true), "sandbox:demo-" + a.number() + ":plan").ok();
            case "POT" -> {
                UUID pot = pot(user);
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("enabled", true);
                s.put("roundTo", 50);
                s.put("multiplier", 2);
                s.put("potId", pot.toString());
                if (!up.as(user, "PUT", up.props.goalsUrl() + "/v1/round-ups", s, null).ok()) {
                    throw new Unreachable("round-ups not set");
                }
                yield true;
            }
            case "READINESS" -> up.as(user, "PUT", up.props.habitsUrl() + "/v1/readiness", Map.of("emergencyFundMonths", 6, "highInterestDebt", false,
                    "horizonYears", 10), null).ok();
            case "SQUAD" -> squad(a, user);
            default -> throw new IllegalStateException(step);
        };
    }

    private void approvePendingMandates(UUID user) {
        JsonNode pending = up.as(user, "GET", up.props.bankUrl() + "/v1/mandates?status=PENDING", null, null).body().path("mandates");
        for (JsonNode m : pending) {
            up.as(user, "POST", up.props.bankUrl() + "/v1/mandates/" + m.path("id").asText() + "/approve", Map.of("upiPin", up.props.upiPin()), null);
        }
    }

    /** The account's holiday pot, made once (found by name if it exists). */
    UUID pot(UUID user) {
        for (JsonNode existing : up.as(user, "GET", up.props.goalsUrl() + "/v1/pots", null, null).body().path("pots")) {
            if (existing.path("name").asText().equals(DemoAccount.POT_NAME)) {
                return UUID.fromString(existing.path("id").asText());
            }
        }
        Reply r = up.as(user, "POST", up.props.goalsUrl() + "/v1/pots", Map.of("name", DemoAccount.POT_NAME, "target",
                String.valueOf(DemoAccount.POT_TARGET), "symbol", DemoAccount.POT_SYMBOL,
                "targetDate", LocalDate.now(clock).plusYears(1).withDayOfMonth(1).toString()), null);
        if (!r.ok()) {
            throw new Unreachable("goals refused the pot: " + r.code());
        }
        return UUID.fromString(r.body().path("id").asText());
    }

    /**
     * Demo accounts save together: each joins the current demo squad, and when that is full it starts the
     * next one, which the accounts after it join.
     */
    private boolean squad(DemoAccount a, UUID user) {
        Optional<String> invite = db.sql("SELECT value FROM facts WHERE name = ?").param("squad:" + SQUAD).query(String.class).optional();
        if (invite.isPresent()) {
            Reply r = up.as(user, "POST", up.props.habitsUrl() + "/v1/squads/join", Map.of("inviteCode", invite.get(), "nickname", a.nickname()), null);
            if (r.ok() || r.code().equals("ALREADY_IN_SQUAD")) {
                return true;
            }
            if (!r.code().equals("SQUAD_FULL")) {
                throw new Unreachable("habits refused joining: " + r.code());
            }
        }
        Reply r = up.as(user, "POST", up.props.habitsUrl() + "/v1/squads", Map.of("name", SQUAD, "nickname", a.nickname()), null);
        if (!r.ok()) {
            throw new Unreachable("habits refused the squad: " + r.code());
        }
        db.sql("INSERT INTO facts (name, value) VALUES (?, ?) ON CONFLICT (name) DO UPDATE SET value = excluded.value")
                .params("squad:" + SQUAD, r.body().path("inviteCode").asText()).update();
        return true;
    }
}
