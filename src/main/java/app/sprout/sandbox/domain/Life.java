package app.sprout.sandbox.domain;

import app.sprout.sandbox.domain.Personas.Persona;
import app.sprout.sandbox.domain.Upstreams.Reply;
import app.sprout.sandbox.domain.Upstreams.Unreachable;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Each fictional person living each trading session, in their own way, through the same APIs as anyone:
 * paid monthly, money added when their Sprout cash runs low, UPI spends at the demo merchants (rounded
 * up for those with round-ups), pots topped up, shares bought (and now and then one sold). Plans buy on
 * their own. Each session is lived once per person; what they do in it is decided by a random draw seeded
 * by the person and the session, so it's varied but repeatable.
 */
@Component
public class Life {

    private static final Logger log = LoggerFactory.getLogger(Life.class);

    static final List<String> MERCHANTS = List.of("monsoonchai@sproutbank", "tiffinbox@sproutbank", "kiranacorner@sproutbank",
            "citymetro@sproutbank", "bookworm@sproutbank", "rechargehub@sproutbank");
    static final List<String> EVERYDAY_SHARES = List.of("KOSHA", "IRONLEAF", "SUNROOT", "CHAIWALA", "NIGHTOWL", "TEALPWR", "GRIDLINE", "THREADS");

    private final JdbcClient db;
    private final Upstreams up;
    private final Accounts accounts;
    private final Setup setup;

    public Life(JdbcClient db, Upstreams up, Accounts accounts, Setup setup) {
        this.db = db;
        this.up = up;
        this.accounts = accounts;
        this.setup = setup;
    }

    /** While the market is open: everyone ready who hasn't lived this session yet, lives it. */
    public void round() {
        JsonNode market = up.send("GET", up.props.marketdataUrl() + "/v1/market", null, Map.of()).body();
        if (market == null || !"OPEN".equals(market.path("state").asText())) {
            return;
        }
        LocalDate session = LocalDate.parse(market.path("sessionDate").asText());
        for (Persona p : Personas.ALL) {
            boolean due = db.sql("SELECT ready AND (last_session IS NULL OR last_session < ?) FROM personas WHERE id = ?")
                    .params(java.sql.Date.valueOf(session), p.id()).query(Boolean.class).optional().orElse(false);
            if (!due) {
                continue;
            }
            try {
                live(p, session);
            } catch (Unreachable e) {
                log.info("{} didn't finish the session of {}: {}", p.name(), session, e.getMessage());
            }
            // lived once, whatever happened: a failure isn't retried into the same session
            db.sql("UPDATE personas SET last_session = ?, sessions_lived = sessions_lived + 1 WHERE id = ?")
                    .params(java.sql.Date.valueOf(session), p.id()).update();
        }
    }

    void live(Persona p, LocalDate session) {
        UUID user = accounts.user(p);
        Random r = new Random((p.id() + session).hashCode());
        int lived = db.sql("SELECT sessions_lived FROM personas WHERE id = ?").param(p.id()).query(Integer.class).single();
        payday(p, session);
        accounts.approvePendingRequests(user);   // anything left waiting from an earlier session
        long cash = Math.round(up.as(user, "GET", up.props.omsUrl() + "/v1/funds", null, null).body().path("cash").asDouble());
        if (cash < 3000) {
            accounts.addMoney(p, 10000, "sandbox:" + p.id() + ":top-up:" + session);
        }
        switch (p.style()) {
            case ROUND_UPS -> {
                for (int i = 0; i < 1 + r.nextInt(2); i++) {
                    spend(p, user, r);
                }
            }
            case GOAL_SAVER -> {
                if (lived % 5 == 0) {
                    UUID pot = setup.pot(p, user);
                    up.as(user, "POST", up.props.goalsUrl() + "/v1/pots/" + pot + "/contributions",
                            Map.of("amount", String.valueOf(1000 + 500 * r.nextInt(5))), "sandbox:" + p.id() + ":pot:" + session);
                }
                if (r.nextInt(3) == 0) {
                    spend(p, user, r);
                }
            }
            case STEADY_PLANS -> {
                if (lived % 10 == 0) {
                    buy(p, user, p.symbol(), 1, session, 0);   // a little extra beside the plan
                }
                if (r.nextInt(3) == 0) {
                    spend(p, user, r);
                }
            }
            case NEW_INVESTOR -> {
                if (lived % 4 == 0) {
                    buy(p, user, r.nextBoolean() ? p.symbol() : EVERYDAY_SHARES.get(r.nextInt(EVERYDAY_SHARES.size())), 1 + r.nextInt(2), session, 0);
                }
            }
            case EXPLORER -> {
                buy(p, user, EVERYDAY_SHARES.get(r.nextInt(EVERYDAY_SHARES.size())), 1, session, 0);
                if (lived % 10 == 9) {
                    sellOne(p, user, session);
                }
            }
        }
    }

    /** The first session of each month, the person is paid by the (fictional) payroll. */
    private void payday(Persona p, LocalDate session) {
        String month = YearMonth.from(session).toString();
        String paid = db.sql("SELECT last_salary_month FROM personas WHERE id = ?").param(p.id()).query(String.class).optional().orElse(null);
        if (month.equals(paid)) {
            return;
        }
        Reply r = up.send("POST", up.props.bankUrl() + "/partner/v1/payouts", Map.of("payeeVpa", accounts.vpa(p),
                "amount", p.salary() + ".00", "reference", "salary:" + p.id() + ":" + month), Map.of("X-Partner-Key", up.props.payrollKey()));
        if (r.ok()) {
            db.sql("UPDATE personas SET last_salary_month = ? WHERE id = ?").params(month, p.id()).update();
        } else {
            log.info("{} wasn't paid for {}: {}", p.name(), month, r.code());
        }
    }

    /** A UPI payment at one of the demo merchants. */
    private void spend(Persona p, UUID user, Random r) {
        String merchant = MERCHANTS.get(r.nextInt(MERCHANTS.size()));
        int rupees = 35 + r.nextInt(600);
        String paise = String.format("%02d", r.nextInt(4) * 25);
        up.as(user, "POST", up.props.bankUrl() + "/v1/payments", Map.of("payeeVpa", merchant, "amount", rupees + "." + paise,
                "upiPin", up.props.upiPin()), null);
    }

    private void buy(Persona p, UUID user, String symbol, int quantity, LocalDate session, int n) {
        up.as(user, "POST", up.props.omsUrl() + "/v1/orders", Map.of("symbol", symbol, "side", "BUY", "quantity", quantity, "orderType", "MARKET",
                "product", "CNC"), "sandbox:" + p.id() + ":buy:" + session + ":" + n);
    }

    /** Now and then, an explorer sells one settled share of something they hold. */
    private void sellOne(Persona p, UUID user, LocalDate session) {
        for (JsonNode h : up.as(user, "GET", up.props.omsUrl() + "/v1/holdings", null, null).body().path("holdings")) {
            if (h.path("quantity").asLong() - h.path("t1Quantity").asLong() >= 1) {
                up.as(user, "POST", up.props.omsUrl() + "/v1/orders", Map.of("symbol", h.path("symbol").asText(), "side", "SELL", "quantity", 1,
                        "orderType", "MARKET", "product", "CNC"), "sandbox:" + p.id() + ":sell:" + session);
                return;
            }
        }
    }
}
