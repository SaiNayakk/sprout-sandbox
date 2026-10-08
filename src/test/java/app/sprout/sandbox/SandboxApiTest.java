package app.sprout.sandbox;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.sprout.contracts.Contracts;
import app.sprout.sandbox.domain.Life;
import app.sprout.sandbox.domain.Setup;
import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The sandbox on a real Postgres against one stand-in for the rest of Sprout, which records every call
 * so the tests can see each demo account set up, given to a visitor, living its sessions and retired.
 */
@Testcontainers
@SpringBootTest(properties = {"spring.config.name=sandbox", "sprout.sandbox.every=1h"})   // the tests run the rounds
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SandboxApiTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    static final ObjectMapper JSON = new ObjectMapper();
    /** Every call the stand-in got: "METHOD path". */
    static final List<String> CALLS = new CopyOnWriteArrayList<>();
    static final Map<String, String> PASSWORDS = new ConcurrentHashMap<>();       // email -> current password
    static final Map<String, String> USERS = new ConcurrentHashMap<>();           // email -> user id
    static final Map<String, String> DISPLAY_NAMES = new ConcurrentHashMap<>();   // email -> the name it goes by
    static final Map<String, List<String>> PENDING = new ConcurrentHashMap<>();   // user -> bank requests waiting
    static final Set<String> APPROVED = ConcurrentHashMap.newKeySet();            // deposit ids approved
    static final Set<String> MANDATE_ACTIVE = ConcurrentHashMap.newKeySet();      // users
    static final Map<String, List<String>> POTS = new ConcurrentHashMap<>();      // user -> pot names
    static final AtomicReference<String> MARKET = new AtomicReference<>("CLOSED");
    static final AtomicReference<String> SESSION = new AtomicReference<>("2026-10-05");
    static final AtomicLong CASH = new AtomicLong(20_000);
    static final HttpServer SPROUT = sprout();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        String base = "http://127.0.0.1:" + SPROUT.getAddress().getPort();
        r.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "&currentSchema=sandbox");
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        for (String s : new String[] {"identity", "marketdata", "accounts", "payments", "bank", "oms", "plans", "habits", "goals", "rewards"}) {
            r.add("sprout.sandbox." + s + "-url", () -> base);
        }
    }

    static final OpenApiInteractionValidator CONTRACT = OpenApiInteractionValidator
            .createForInlineApiSpecification(Contracts.read(Contracts.SANDBOX_V3))
            .withBasePathOverride("/")
            .withLevelResolver(LevelResolver.create().withLevel("validation.request", ValidationReport.Level.IGNORE).build())
            .build();
    static final ResultMatcher MATCHES_CONTRACT = openApi().isValid(CONTRACT);

    @TestConfiguration
    static class TestClock {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(Instant.parse("2026-10-05T05:30:00Z"));
        }
    }

    @Autowired MutableClock clock;
    @Autowired JdbcClient db;
    @Autowired MockMvc mvc;
    @Autowired Setup setup;
    @Autowired Life life;

    static long count(String call) {
        return CALLS.stream().filter(c -> c.startsWith(call)).count();
    }

    ResultActions start(String name) throws Exception {
        return mvc.perform(post("/v3/demo-sessions").contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(Map.of("name", name))));
    }

    @Test
    @Order(1)
    void beforeAnyAccountIsReadyVisitorsAreToldToWaitAndNamesAreChecked() throws Exception {
        mvc.perform(get("/v3/demo")).andExpect(status().isOk()).andExpect(MATCHES_CONTRACT)
                .andExpect(jsonPath("$.ready").value(false)).andExpect(jsonPath("$.endsAfterMinutes").value(120));
        start("Asha").andExpect(status().isServiceUnavailable()).andExpect(MATCHES_CONTRACT);
        for (String bad : List.of("", "   ", "<script>", "R2D2", "a".repeat(41))) {
            start(bad).andExpect(status().isBadRequest()).andExpect(MATCHES_CONTRACT);
        }
    }

    @Test
    @Order(2)
    void aFewAccountsAreKeptWarmEachSetUpThroughTheSameStepsAsACustomerOnce() {
        for (int i = 0; i < 12; i++) {
            setup.round();
        }
        assertThat(count("POST /internal/v1/demo-users")).as("three warm accounts, one demo user each").isEqualTo(3);
        assertThat(count("POST /v1/accounts")).as("a bank account and a Sprout account each").isEqualTo(6);
        assertThat(count("POST /v1/requests/")).as("each first deposit approved in the bank with the PIN").isEqualTo(3);
        assertThat(count("POST /v1/mandates/")).as("AutoPay approved for round-ups").isEqualTo(3);
        assertThat(count("POST /v1/plans")).isEqualTo(3);
        assertThat(count("POST /v1/pots")).as("a holiday pot each").isEqualTo(3);
        assertThat(count("PUT /v1/round-ups")).isEqualTo(3);
        assertThat(count("POST /v1/squads ")).as("one demo squad").isEqualTo(1);
        assertThat(count("POST /v1/squads/join")).isEqualTo(2);
        assertThat(DISPLAY_NAMES.values()).as("nothing about a visitor is assumed before they arrive")
                .allMatch(n -> n.startsWith("Demo Customer "));
        long calls = CALLS.size();
        setup.round();
        assertThat(CALLS.size()).as("set up once, and the pool is full: nothing more to do").isEqualTo(calls);
    }

    @Test
    @Order(3)
    void eachSessionIsLivedOnceByEachAccount() {
        life.round();
        assertThat(count("POST /v1/orders")).as("the market is closed").isZero();
        MARKET.set("OPEN");
        life.round();
        assertThat(count("POST /partner/v1/payouts")).as("each paid for October").isEqualTo(3);
        long spends = count("POST /v1/payments");
        assertThat(spends).as("each spent at least once (rounded up into the pot)").isGreaterThanOrEqualTo(3);
        assertThat(count("POST /v1/orders")).as("the first session buys a share").isEqualTo(3);
        life.round();
        assertThat(count("POST /v1/payments")).as("a session is lived once").isEqualTo(spends);
        SESSION.set("2026-10-06");
        life.round();
        assertThat(count("POST /partner/v1/payouts")).as("paid once a month").isEqualTo(3);
        SESSION.set("2026-11-02");
        CASH.set(1000);
        long deposits = count("POST /v1/deposits");
        life.round();
        assertThat(count("POST /partner/v1/payouts")).as("November's pay").isEqualTo(6);
        assertThat(count("POST /v1/deposits")).as("money added when cash ran low").isEqualTo(deposits + 3);
        CASH.set(20_000);
    }

    @Test
    @Order(4)
    void eachVisitorGetsAnAccountOfTheirOwnWithTheMostHistoryNamedAsTheyChose() throws Exception {
        db.sql("UPDATE demo_accounts SET sessions_lived = 40 WHERE number = (SELECT max(number) FROM demo_accounts)").update();
        mvc.perform(get("/v3/demo")).andExpect(MATCHES_CONTRACT).andExpect(jsonPath("$.ready").value(true))
                .andExpect(jsonPath("$.sessionsLived").value(40));
        JsonNode first = JSON.readTree(start("  Asha   Rao ").andExpect(status().isCreated()).andExpect(MATCHES_CONTRACT)
                .andReturn().getResponse().getContentAsString());
        assertThat(first.path("name").asText()).isEqualTo("Asha Rao");
        assertThat(first.path("sessionsLived").asInt()).as("the warm account with the most history").isEqualTo(40);
        assertThat(Instant.parse(first.path("endsAt").asText())).isEqualTo(clock.instant().plus(Duration.ofHours(2)));
        assertThat(DISPLAY_NAMES).containsValue("Asha Rao");
        JsonNode second = JSON.readTree(start("Zo\u00eb D'Souza").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertThat(second.path("accessToken").asText()).as("someone else's account").isNotEqualTo(first.path("accessToken").asText());
        assertThat(db.sql("SELECT count(*) FROM demo_accounts WHERE claimed_at IS NOT NULL").query(Integer.class).single()).isEqualTo(2);

        setup.round();
        assertThat(db.sql("SELECT count(*) FROM demo_accounts WHERE claimed_at IS NULL AND retired_at IS NULL").query(Integer.class).single())
                .as("new accounts start warming in their place").isEqualTo(3);
        long paid = count("POST /partner/v1/payouts");
        SESSION.set("2026-12-01");
        life.round();
        assertThat(count("POST /partner/v1/payouts")).as("claimed accounts keep living; the new ones aren't ready yet").isEqualTo(paid + 3);
    }

    @Test
    @Order(5)
    void whenAVisitorsTimeIsUpTheirAccountStopsLivingAndCantBeSignedInTo() {
        clock.advance(Duration.ofHours(2).plusSeconds(1));
        setup.round();
        assertThat(db.sql("SELECT count(*) FROM demo_accounts WHERE retired_at IS NOT NULL").query(Integer.class).single()).isEqualTo(2);
        assertThat(DISPLAY_NAMES.values().stream().filter(n -> n.equals("Closed demo account")).count()).as("closed to sign-in").isEqualTo(2);
        long paid = count("POST /partner/v1/payouts");
        SESSION.set("2027-01-04");
        life.round();
        assertThat(count("POST /partner/v1/payouts")).as("only the warm account that is ready lives on").isEqualTo(paid + 1);
    }

    // ── the stand-in for the rest of Sprout ──────────────────────────────────

    static void reply(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    static HttpServer sprout() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/", ex -> {
                String method = ex.getRequestMethod();
                String path = ex.getRequestURI().getPath();
                String query = ex.getRequestURI().getQuery();
                String user = ex.getRequestHeaders().getFirst("X-User-Id");
                byte[] raw = ex.getRequestBody().readAllBytes();
                JsonNode body = JSON.readTree(raw.length == 0 ? "{}".getBytes() : raw);
                CALLS.add(method + " " + path + (path.equals("/v1/squads") ? " " : ""));
                switch (method + " " + path) {
                    case "POST /internal/v1/demo-users" -> {
                        String email = body.path("email").asText();
                        PASSWORDS.put(email, body.path("password").asText());
                        DISPLAY_NAMES.put(email, body.path("displayName").asText());
                        String id = USERS.computeIfAbsent(email, e -> UUID.randomUUID().toString());
                        reply(ex, 200, Map.of("id", id, "email", email, "displayName", body.path("displayName").asText(), "totpEnabled", false,
                                "createdAt", "2026-10-05T00:00:00Z"));
                    }
                    case "POST /v1/sessions" -> {
                        boolean right = body.path("password").asText().equals(PASSWORDS.get(body.path("email").asText()));
                        reply(ex, right ? 200 : 401, right ? Map.of("status", "AUTHENTICATED", "tokens", Map.of("accessToken",
                                "token-for-" + body.path("email").asText(), "tokenType", "Bearer", "expiresIn", 900, "refreshToken", "r"))
                                : Map.of("code", "INVALID_CREDENTIALS"));
                    }
                    case "POST /v1/accounts" -> reply(ex, 201, Map.of("vpa", "p" + user.substring(0, 6) + "@sproutbank"));
                    case "GET /v1/accounts/me" -> reply(ex, 200, Map.of("vpa", "p" + user.substring(0, 6) + "@sproutbank"));
                    case "POST /v1/deposits" -> {
                        String id = UUID.nameUUIDFromBytes((user + ex.getRequestHeaders().getFirst("Idempotency-Key")).getBytes()).toString();
                        PENDING.computeIfAbsent(user, u -> new CopyOnWriteArrayList<>()).add(id);
                        reply(ex, 201, Map.of("id", id, "status", "AWAITING_APPROVAL"));
                    }
                    case "POST /v1/mandates" -> {
                        PENDING.computeIfAbsent(user, u -> new CopyOnWriteArrayList<>()).add("mandate");
                        reply(ex, 201, Map.of("status", "AWAITING_APPROVAL"));
                    }
                    case "GET /v1/mandates/me" -> reply(ex, 200, Map.of("status", MANDATE_ACTIVE.contains(user) ? "ACTIVE" : "AWAITING_APPROVAL"));
                    case "GET /v1/requests" -> reply(ex, 200, Map.of("requests", PENDING.getOrDefault(user, List.of()).stream()
                            .filter(id -> !id.equals("mandate") && !APPROVED.contains(id)).map(id -> Map.of("id", id)).toList()));
                    case "GET /v1/mandates" -> reply(ex, 200, Map.of("mandates", PENDING.getOrDefault(user, List.of()).contains("mandate")
                            && !MANDATE_ACTIVE.contains(user) ? List.of(Map.of("id", "m-" + user)) : List.of()));
                    case "GET /v1/pots" -> reply(ex, 200, Map.of("pots", POTS.getOrDefault(user, List.of()).stream()
                            .map(n -> Map.of("id", UUID.nameUUIDFromBytes((user + n).getBytes()).toString(), "name", n)).toList()));
                    case "POST /v1/pots" -> {
                        String name = body.path("name").asText();
                        POTS.computeIfAbsent(user, u -> new CopyOnWriteArrayList<>()).add(name);
                        reply(ex, 201, Map.of("id", UUID.nameUUIDFromBytes((user + name).getBytes()).toString()));
                    }
                    case "POST /v1/squads" -> reply(ex, 201, Map.of("id", UUID.randomUUID().toString(), "inviteCode", "SQ-" + body.path("name").asText()));
                    case "GET /v1/referrals/me" -> reply(ex, 200, Map.of("code", "SPR-" + user.substring(0, 6)));
                    case "GET /v1/market" -> reply(ex, 200, Map.of("state", MARKET.get(), "sessionDate", SESSION.get()));
                    case "GET /v1/funds" -> reply(ex, 200, Map.of("cash", CASH.get() + ".00"));
                    case "GET /v1/holdings" -> reply(ex, 200, Map.of("holdings", List.of(Map.of("symbol", "KOSHA", "quantity", 3, "t1Quantity", 0))));
                    default -> {
                        if (method.equals("POST") && path.startsWith("/v1/requests/")) {
                            APPROVED.add(path.split("/")[3]);
                        } else if (method.equals("POST") && path.startsWith("/v1/mandates/")) {
                            MANDATE_ACTIVE.add(user);
                        } else if (method.equals("GET") && path.startsWith("/v1/deposits/")) {
                            reply(ex, 200, Map.of("status", APPROVED.contains(path.split("/")[3]) ? "COMPLETED" : "AWAITING_APPROVAL"));
                            return;
                        }
                        reply(ex, method.equals("POST") ? 201 : 200, Map.of("ok", true, "query", query == null ? "" : query));
                    }
                }
            });
            s.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            s.start();
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
