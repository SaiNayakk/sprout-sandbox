package app.sprout.sandbox.domain;

import app.sprout.sandbox.domain.Personas.Group;
import app.sprout.sandbox.domain.Personas.Persona;
import app.sprout.sandbox.domain.Upstreams.Unreachable;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Visitors exploring as a fictional person: given whoever in their chosen group was explored least
 * recently (and is set up), with an ordinary signed-in session as them.
 */
@Service
public class Demo {

    private static final Logger log = LoggerFactory.getLogger(Demo.class);

    public record Started(Persona persona, JsonNode tokens) {}

    private final JdbcClient db;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Accounts accounts;

    public Demo(JdbcClient db, TransactionTemplate tx, Clock clock, Accounts accounts) {
        this.db = db;
        this.tx = tx;
        this.clock = clock;
        this.accounts = accounts;
    }

    /** {@code group} is a {@link Group} name, or ANY. */
    public Started start(String group) {
        List<String> ids;
        if ("ANY".equals(group)) {
            ids = Personas.ALL.stream().map(Persona::id).toList();
        } else {
            Group g;
            try {
                g = Group.valueOf(group == null ? "" : group);
            } catch (IllegalArgumentException e) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Choose WOMEN, MEN, NON_BINARY_AND_OTHER or ANY.");
            }
            ids = Personas.ALL.stream().filter(p -> p.group() == g).map(Persona::id).toList();
        }
        // two tries: if one person can't be signed in now, the next least recent can
        for (int attempt = 0; attempt < 2; attempt++) {
            Optional<Started> s = tx.execute(t -> {
                Optional<String> next = db.sql("SELECT id FROM personas WHERE ready AND id IN (:ids) "
                                + "ORDER BY last_served_at NULLS FIRST, served, id LIMIT 1 FOR UPDATE SKIP LOCKED")
                        .param("ids", ids).query(String.class).optional();
                if (next.isEmpty()) {
                    return Optional.<Started>empty();
                }
                Persona p = Personas.byId(next.get()).orElseThrow();
                // marked served first: if signing in fails, the next try (and the next visitor) gets someone else
                db.sql("UPDATE personas SET last_served_at = ?, served = served + 1 WHERE id = ?")
                        .params(Timestamp.from(clock.instant()), p.id()).update();
                try {
                    return Optional.of(new Started(p, accounts.signIn(p)));
                } catch (Unreachable e) {
                    log.warn("Couldn't sign a visitor in as {}: {}", p.name(), e.getMessage());
                    return Optional.<Started>empty();
                }
            });
            if (s.isPresent()) {
                return s.get();
            }
        }
        throw new ApiException(ErrorCode.UPSTREAM_UNAVAILABLE, "Everyone in that group is still being set up, or busy. Try again in a minute.", 30,
                Map.of());
    }
}
