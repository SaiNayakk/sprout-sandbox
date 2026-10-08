package app.sprout.sandbox.domain;

import app.sprout.sandbox.domain.Upstreams.Reply;
import app.sprout.sandbox.domain.Upstreams.Unreachable;
import com.fasterxml.jackson.databind.JsonNode;
import java.security.SecureRandom;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Who each demo account is in the rest of Sprout, signing it in, closing it to sign-in, and adding money
 * to its Sprout balance the way anyone does (a deposit approved in the bank with the PIN).
 *
 * <p>No password is ever stored: each sign-in sets a new long random one first (identity's demo users
 * allow that, to Sprout services only) and uses it at once.
 */
@Component
public class Accounts {

    private static final char[] ALPHABET = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient db;
    private final Upstreams up;

    public Accounts(JdbcClient db, Upstreams up) {
        this.db = db;
        this.up = up;
    }

    String email(DemoAccount a) {
        return "demo-" + a.number() + "@" + up.props.emailDomain();
    }

    /** Signs the account in as {@code displayName} (creating its demo user the first time) and returns identity's tokens. */
    JsonNode signIn(DemoAccount a, String displayName) {
        String password = password();
        demoUser(a, displayName, password);
        Reply in = up.send("POST", up.props.identityUrl() + "/v1/sessions", Map.of("email", email(a), "password", password), Map.of());
        if (!in.ok() || !in.body().path("status").asText().equals("AUTHENTICATED")) {
            throw new Unreachable("identity didn't sign it in: " + in.status() + " " + in.code());
        }
        return in.body().path("tokens");
    }

    /** No one can sign in to it again: its password becomes one nobody knows. */
    void close(DemoAccount a) {
        demoUser(a, "Closed demo account", password());
    }

    private void demoUser(DemoAccount a, String displayName, String password) {
        Reply made = up.send("POST", up.props.identityUrl() + "/internal/v1/demo-users", Map.of("email", email(a), "password", password,
                "displayName", displayName), Map.of("X-Service-Key", up.props.serviceKey()));
        if (!made.ok()) {
            throw new Unreachable("identity refused the demo user: " + made.status() + " " + made.code());
        }
        db.sql("UPDATE demo_accounts SET user_id = ? WHERE id = ?").params(UUID.fromString(made.body().path("id").asText()), a.id()).update();
    }

    Optional<UUID> knownUser(DemoAccount a) {
        return db.sql("SELECT user_id FROM demo_accounts WHERE id = ?").param(a.id()).query(UUID.class).optional().filter(u -> u != null);
    }

    UUID user(DemoAccount a) {
        return knownUser(a).orElseThrow(() -> new Unreachable("demo account " + a.number() + " has no sign-in yet"));
    }

    String vpa(DemoAccount a) {
        return db.sql("SELECT bank_vpa FROM demo_accounts WHERE id = ?").param(a.id()).query(String.class).optional().filter(v -> v != null)
                .orElseThrow(() -> new Unreachable("demo account " + a.number() + " has no bank account yet"));
    }

    /**
     * Adds money to the account's Sprout balance: a deposit (under {@code key}, so asking again is the same
     * deposit), approved in the bank with the PIN. True once it has arrived.
     */
    boolean addMoney(DemoAccount a, int rupees, String key) {
        UUID user = user(a);
        Reply d = up.as(user, "POST", up.props.paymentsUrl() + "/v1/deposits", Map.of("amount", String.valueOf(rupees)), key);
        if (!d.ok()) {
            throw new Unreachable("payments refused the deposit: " + d.code());
        }
        approvePendingRequests(user);
        Reply now = up.as(user, "GET", up.props.paymentsUrl() + "/v1/deposits/" + d.body().path("id").asText(), null, null);
        return "COMPLETED".equals(now.body().path("status").asText());
    }

    void approvePendingRequests(UUID user) {
        for (JsonNode r : up.as(user, "GET", up.props.bankUrl() + "/v1/requests?status=PENDING", null, null).body().path("requests")) {
            up.as(user, "POST", up.props.bankUrl() + "/v1/requests/" + r.path("id").asText() + "/approve", Map.of("upiPin", up.props.upiPin()), null);
        }
    }

    private static String password() {
        StringBuilder b = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            b.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return b.toString();
    }
}
