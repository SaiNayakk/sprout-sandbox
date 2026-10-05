package app.sprout.sandbox.domain;

import app.sprout.sandbox.domain.Personas.Persona;
import app.sprout.sandbox.domain.Personas.Style;
import app.sprout.sandbox.domain.Upstreams.Reply;
import app.sprout.sandbox.domain.Upstreams.Unreachable;
import com.fasterxml.jackson.databind.JsonNode;
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
 * Sets each fictional person up the way a real customer sets up, through the same APIs: a sign-in, a
 * bank account with a UPI PIN, a Sprout account (KYC), a first deposit approved in the bank, and then
 * what their way of investing needs (AutoPay and round-ups, a plan, a pot), readiness, a squad, and a
 * friend's referral code. One step per person per round; every step is safe to repeat, and a step that
 * can't be done yet (a service down, a friend not set up) is tried again next round.
 */
@Component
public class Setup {

    private static final Logger log = LoggerFactory.getLogger(Setup.class);

    static final String[] STEPS = {"IDENTITY", "BANK", "ACCOUNT", "DEPOSIT", "AUTOPAY", "PLAN", "POT", "READINESS", "SQUAD", "REFERRAL"};
    static final String[] SQUADS = {"Monsoon Savers", "Chai and Compounding", "The Long Game"};

    private final JdbcClient db;
    private final Upstreams up;
    private final Accounts accounts;

    public Setup(JdbcClient db, Upstreams up, Accounts accounts) {
        this.db = db;
        this.up = up;
        this.accounts = accounts;
    }

    /** Takes every person not ready yet one step further. */
    public void round() {
        for (Persona p : Personas.ALL) {
            db.sql("INSERT INTO personas (id) VALUES (?) ON CONFLICT (id) DO NOTHING").param(p.id()).update();
            int step = db.sql("SELECT step FROM personas WHERE id = ?").param(p.id()).query(Integer.class).single();
            if (step >= STEPS.length) {
                continue;
            }
            try {
                if (step(p, STEPS[step])) {
                    boolean ready = step + 1 >= STEPS.length;
                    db.sql("UPDATE personas SET step = ?, ready = ? WHERE id = ?").params(step + 1, ready, p.id()).update();
                    if (ready) {
                        log.info("{} is set up and ready to explore", p.name());
                    }
                }
            } catch (Unreachable e) {
                log.info("{}: {} not done yet ({})", p.name(), STEPS[step], e.getMessage());
            }
        }
    }

    /** Does one step; true once it's done. */
    boolean step(Persona p, String step) {
        UUID user = step.equals("IDENTITY") ? null : accounts.user(p);
        return switch (step) {
            case "IDENTITY" -> {
                accounts.signIn(p);   // creates the demo user (and proves it signs in)
                yield true;
            }
            case "BANK" -> {
                Reply r = up.as(user, "POST", up.props.bankUrl() + "/v1/accounts", Map.of("holderName", p.name(), "upiPin", up.props.upiPin()), null);
                if (!r.ok() && !r.code().equals("ACCOUNT_EXISTS")) {
                    throw new Unreachable("the bank refused the account: " + r.code());
                }
                String vpa = up.as(user, "GET", up.props.bankUrl() + "/v1/accounts/me", null, null).body().path("vpa").asText();
                db.sql("UPDATE personas SET bank_vpa = ? WHERE id = ?").params(vpa, p.id()).update();
                yield true;
            }
            case "ACCOUNT" -> {
                Reply r = up.as(user, "POST", up.props.accountsUrl() + "/v1/accounts", Map.of("legalName", p.name(), "dateOfBirth", p.dateOfBirth(),
                        "pan", Personas.pan(p), "bankVpa", accounts.vpa(p)), null);
                if (!r.ok() && !r.code().equals("ACCOUNT_EXISTS")) {
                    throw new Unreachable("accounts refused: " + r.code());
                }
                yield true;
            }
            case "DEPOSIT" -> accounts.addMoney(p, 25000, "sandbox:" + p.id() + ":first-deposit");
            case "AUTOPAY" -> {
                if (p.style() != Style.ROUND_UPS) {
                    yield true;
                }
                up.as(user, "POST", up.props.paymentsUrl() + "/v1/mandates", Map.of("maxAmount", "1000"), "sandbox:" + p.id() + ":autopay");
                approvePendingMandates(user);
                yield "ACTIVE".equals(up.as(user, "GET", up.props.paymentsUrl() + "/v1/mandates/me", null, null).body().path("status").asText());
            }
            case "PLAN" -> {
                if (p.style() != Style.STEADY_PLANS) {
                    yield true;
                }
                Reply r = up.as(user, "POST", up.props.plansUrl() + "/v1/plans", Map.of("symbol", p.symbol(), "amount",
                        String.valueOf(p.salary() / 10), "dayOfMonth", 5, "startNow", true), "sandbox:" + p.id() + ":plan");
                yield r.ok();
            }
            case "POT" -> {
                if (p.goal() == null) {
                    yield true;
                }
                UUID pot = pot(p, user);
                if (p.style() == Style.ROUND_UPS) {
                    Map<String, Object> s = new LinkedHashMap<>();
                    s.put("enabled", true);
                    s.put("roundTo", p.salary() > 44000 ? 50 : 10);
                    s.put("multiplier", 2);
                    s.put("potId", pot.toString());
                    if (!up.as(user, "PUT", up.props.goalsUrl() + "/v1/round-ups", s, null).ok()) {
                        throw new Unreachable("round-ups not set");
                    }
                }
                yield true;
            }
            case "READINESS" -> up.as(user, "PUT", up.props.habitsUrl() + "/v1/readiness", Map.of("emergencyFundMonths", 6, "highInterestDebt", false,
                    "horizonYears", 10), null).ok();
            case "SQUAD" -> squad(p, user);
            case "REFERRAL" -> referral(p, user);
            default -> throw new IllegalStateException(step);
        };
    }

    private void approvePendingMandates(UUID user) {
        JsonNode pending = up.as(user, "GET", up.props.bankUrl() + "/v1/mandates?status=PENDING", null, null).body().path("mandates");
        for (JsonNode m : pending) {
            up.as(user, "POST", up.props.bankUrl() + "/v1/mandates/" + m.path("id").asText() + "/approve", Map.of("upiPin", up.props.upiPin()), null);
        }
    }

    /** The person's pot for their goal, made once (found by name if it exists). */
    UUID pot(Persona p, UUID user) {
        for (JsonNode existing : up.as(user, "GET", up.props.goalsUrl() + "/v1/pots", null, null).body().path("pots")) {
            if (existing.path("name").asText().equals(p.goal())) {
                return UUID.fromString(existing.path("id").asText());
            }
        }
        Map<String, Object> body = new LinkedHashMap<>(Map.of("name", p.goal(), "target", String.valueOf(p.goalTarget()), "symbol", p.symbol()));
        if (p.style() == Style.GOAL_SAVER) {
            body.put("targetDate", LocalDate.now().plusYears(2).withDayOfMonth(1).toString());
        }
        Reply r = up.as(user, "POST", up.props.goalsUrl() + "/v1/pots", body, null);
        if (!r.ok()) {
            throw new Unreachable("goals refused the pot: " + r.code());
        }
        return UUID.fromString(r.body().path("id").asText());
    }

    /** Three mixed squads of five: the first person of each starts it, the others join with its invite code. */
    private boolean squad(Persona p, UUID user) {
        int index = Personas.ALL.indexOf(p);
        String name = SQUADS[index % SQUADS.length];
        String nickname = p.name().substring(0, p.name().indexOf(' '));
        Optional<String> invite = db.sql("SELECT value FROM facts WHERE name = ?").param("squad:" + name).query(String.class).optional();
        if (invite.isEmpty()) {
            if (index >= SQUADS.length) {
                return false;   // its founder isn't set up yet
            }
            Reply r = up.as(user, "POST", up.props.habitsUrl() + "/v1/squads", Map.of("name", name, "nickname", nickname), null);
            if (!r.ok()) {
                throw new Unreachable("habits refused the squad: " + r.code());
            }
            db.sql("INSERT INTO facts (name, value) VALUES (?, ?) ON CONFLICT (name) DO NOTHING").params("squad:" + name,
                    r.body().path("inviteCode").asText()).update();
        } else {
            Reply r = up.as(user, "POST", up.props.habitsUrl() + "/v1/squads/join", Map.of("inviteCode", invite.get(), "nickname", nickname), null);
            if (!r.ok() && r.status() != 409) {
                throw new Unreachable("habits refused joining: " + r.code());
            }
        }
        up.as(user, "PUT", up.props.habitsUrl() + "/v1/habits/me/privacy", Map.of("showInvestedRange", index % 2 == 0), null);
        return true;
    }

    /** Enters the friend's referral code once the friend is set up enough to have one. */
    private boolean referral(Persona p, UUID user) {
        if (p.referredBy() == null) {
            return true;
        }
        Persona friend = Personas.byId(p.referredBy()).orElseThrow();
        Optional<UUID> friendUser = accounts.knownUser(friend);
        if (friendUser.isEmpty()) {
            return false;
        }
        Reply mine = up.as(friendUser.get(), "GET", up.props.rewardsUrl() + "/v1/referrals/me", null, null);
        if (!mine.ok()) {
            return false;   // the friend's account isn't open yet
        }
        Reply r = up.as(user, "POST", up.props.rewardsUrl() + "/v1/referrals/claim", Map.of("code", mine.body().path("code").asText()), null);
        return r.ok() || r.code().equals("ALREADY_REFERRED");
    }

    static List<String> steps() {
        return List.of(STEPS);
    }
}
