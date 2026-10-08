package app.sprout.sandbox.domain;

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
 * Each demo account living each trading session through the same APIs as anyone, warming for a visitor
 * and while it is theirs: paid monthly, money added when Sprout cash runs low, UPI spends at the demo shops
 * (rounded up into the pot), the pot topped up, a share bought now and then and one sold now and then.
 * The plan buys on its own. Each session is lived once per account; what it does in it is decided by a
 * random draw seeded by the account and the session, so it's varied but repeatable.
 */
@Component
public class Life {

    private static final Logger log = LoggerFactory.getLogger(Life.class);

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

    /** While the market is open: every living account that hasn't lived this session yet, lives it. */
    public void round() {
        JsonNode market = up.send("GET", up.props.marketdataUrl() + "/v1/market", null, Map.of()).body();
        if (market == null || !"OPEN".equals(market.path("state").asText())) {
            return;
        }
        LocalDate session = LocalDate.parse(market.path("sessionDate").asText());
        List<DemoAccount> due = db.sql("""
                        SELECT id, number FROM demo_accounts
                        WHERE ready AND retired_at IS NULL AND (last_session IS NULL OR last_session < ?) ORDER BY number""")
                .param(java.sql.Date.valueOf(session))
                .query((rs, n) -> new DemoAccount(rs.getObject(1, UUID.class), rs.getLong(2))).list();
        for (DemoAccount a : due) {
            try {
                live(a, session);
            } catch (Unreachable e) {
                log.info("Demo account {} didn't finish the session of {}: {}", a.number(), session, e.getMessage());
            }
            // lived once, whatever happened: a failure isn't retried into the same session
            db.sql("UPDATE demo_accounts SET last_session = ?, sessions_lived = sessions_lived + 1 WHERE id = ?")
                    .params(java.sql.Date.valueOf(session), a.id()).update();
        }
    }

    void live(DemoAccount a, LocalDate session) {
        UUID user = accounts.user(a);
        Random r = new Random((a.id() + session.toString()).hashCode());
        int lived = db.sql("SELECT sessions_lived FROM demo_accounts WHERE id = ?").param(a.id()).query(Integer.class).single();
        payday(a, session);
        accounts.approvePendingRequests(user);   // anything left waiting from an earlier session
        long cash = Math.round(up.as(user, "GET", up.props.omsUrl() + "/v1/funds", null, null).body().path("cash").asDouble());
        if (cash < 3000) {
            accounts.addMoney(a, 10000, "sandbox:demo-" + a.number() + ":top-up:" + session);
        }
        for (int i = 0; i < 1 + r.nextInt(2); i++) {
            spend(user, r);   // rounded up into the pot
        }
        if (lived % 5 == 0) {
            up.as(user, "POST", up.props.goalsUrl() + "/v1/pots/" + setup.pot(user) + "/contributions",
                    Map.of("amount", String.valueOf(500 + 500 * r.nextInt(4))), "sandbox:demo-" + a.number() + ":pot:" + session);
        }
        if (lived % 3 == 0) {
            List<String> shares = DemoAccount.EVERYDAY_SHARES;
            up.as(user, "POST", up.props.omsUrl() + "/v1/orders", Map.of("symbol", shares.get(r.nextInt(shares.size())), "side", "BUY",
                    "quantity", 1, "orderType", "MARKET", "product", "CNC"), "sandbox:demo-" + a.number() + ":buy:" + session);
        }
        if (lived % 10 == 9) {
            sellOne(a, user, session);
        }
    }

    /** The first session of each month, the account is paid by the (fictional) payroll. */
    private void payday(DemoAccount a, LocalDate session) {
        String month = YearMonth.from(session).toString();
        String paid = db.sql("SELECT last_salary_month FROM demo_accounts WHERE id = ?").param(a.id()).query(String.class).optional().orElse(null);
        if (month.equals(paid)) {
            return;
        }
        Reply r = up.send("POST", up.props.bankUrl() + "/partner/v1/payouts", Map.of("payeeVpa", accounts.vpa(a),
                "amount", DemoAccount.SALARY + ".00", "reference", "salary:demo-" + a.number() + ":" + month), Map.of("X-Partner-Key", up.props.payrollKey()));
        if (r.ok()) {
            db.sql("UPDATE demo_accounts SET last_salary_month = ? WHERE id = ?").params(month, a.id()).update();
        } else {
            log.info("Demo account {} wasn't paid for {}: {}", a.number(), month, r.code());
        }
    }

    /** A UPI payment at one of the demo shops. */
    private void spend(UUID user, Random r) {
        String merchant = DemoAccount.MERCHANTS.get(r.nextInt(DemoAccount.MERCHANTS.size()));
        int rupees = 35 + r.nextInt(600);
        String paise = String.format("%02d", r.nextInt(4) * 25);
        up.as(user, "POST", up.props.bankUrl() + "/v1/payments", Map.of("payeeVpa", merchant, "amount", rupees + "." + paise,
                "upiPin", up.props.upiPin()), null);
    }

    /** Now and then, one settled share of something held is sold. */
    private void sellOne(DemoAccount a, UUID user, LocalDate session) {
        for (JsonNode h : up.as(user, "GET", up.props.omsUrl() + "/v1/holdings", null, null).body().path("holdings")) {
            if (h.path("quantity").asLong() - h.path("t1Quantity").asLong() >= 1) {
                up.as(user, "POST", up.props.omsUrl() + "/v1/orders", Map.of("symbol", h.path("symbol").asText(), "side", "SELL", "quantity", 1,
                        "orderType", "MARKET", "product", "CNC"), "sandbox:demo-" + a.number() + ":sell:" + session);
                return;
            }
        }
    }
}
