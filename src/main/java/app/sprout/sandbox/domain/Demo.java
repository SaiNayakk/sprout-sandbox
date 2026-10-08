package app.sprout.sandbox.domain;

import app.sprout.sandbox.config.SandboxProperties;
import app.sprout.sandbox.domain.Upstreams.Unreachable;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Visitors trying Sprout: each is given the warm demo account with the most history, nobody else's, named
 * as they choose, with an ordinary signed-in session, until {@code keepFor} has passed.
 */
@Service
public class Demo {

    private static final Logger log = LoggerFactory.getLogger(Demo.class);
    /** Letters (any script) and the punctuation names use; it must start with a letter. */
    private static final Pattern NAME = Pattern.compile("^\\p{L}[\\p{L}\\p{M} .,'-]*$");

    public record Status(boolean ready, Integer sessionsLived, long endsAfterMinutes) {}

    public record Started(String name, int sessionsLived, Instant endsAt, JsonNode tokens) {}

    private record Available(DemoAccount account, int sessionsLived) {}

    private final JdbcClient db;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Accounts accounts;
    private final SandboxProperties props;

    public Demo(JdbcClient db, TransactionTemplate tx, Clock clock, Accounts accounts, SandboxProperties props) {
        this.db = db;
        this.tx = tx;
        this.clock = clock;
        this.accounts = accounts;
        this.props = props;
    }

    public Status status() {
        Optional<Integer> best = db.sql("""
                        SELECT sessions_lived FROM demo_accounts WHERE ready AND claimed_at IS NULL AND retired_at IS NULL
                        ORDER BY sessions_lived DESC LIMIT 1""").query(Integer.class).optional();
        return new Status(best.isPresent(), best.orElse(null), props.keepFor().toMinutes());
    }

    public Started start(String name) {
        String clean = name == null ? "" : name.trim().replaceAll("\\s+", " ");
        if (clean.isEmpty() || clean.length() > 40 || !NAME.matcher(clean).matches()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Tell us what to call you: letters, spaces and . , - ' only, up to 40 characters.");
        }
        try {
            Optional<Started> s = tx.execute(t -> {
                Optional<Available> next = db.sql("""
                                SELECT id, number, sessions_lived FROM demo_accounts
                                WHERE ready AND claimed_at IS NULL AND retired_at IS NULL
                                ORDER BY sessions_lived DESC, number LIMIT 1 FOR UPDATE SKIP LOCKED""")
                        .query((rs, n) -> new Available(new DemoAccount(rs.getObject(1, UUID.class), rs.getLong(2)), rs.getInt(3))).optional();
                if (next.isEmpty()) {
                    return Optional.<Started>empty();
                }
                Instant now = clock.instant();
                Instant ends = now.plus(props.keepFor());
                // signed in first: if that fails, the account isn't given out (the transaction rolls back)
                JsonNode tokens = accounts.signIn(next.get().account(), clean);
                db.sql("UPDATE demo_accounts SET claimed_at = ?, claimed_name = ?, ends_at = ? WHERE id = ?")
                        .params(Timestamp.from(now), clean, Timestamp.from(ends), next.get().account().id()).update();
                return Optional.of(new Started(clean, next.get().sessionsLived(), ends, tokens));
            });
            if (s.isPresent()) {
                return s.get();
            }
        } catch (Unreachable e) {
            log.warn("Couldn't sign a visitor in to a demo account: {}", e.getMessage());
            throw new ApiException(ErrorCode.UPSTREAM_UNAVAILABLE, "Sprout can't sign you in to a demo account right now. Try again in a minute.", 30,
                    Map.of());
        }
        throw new ApiException(ErrorCode.UPSTREAM_UNAVAILABLE, "No demo account is ready yet; a new one is getting set up. Try again in a few minutes.",
                60, Map.of());
    }
}
