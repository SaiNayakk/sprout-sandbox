package app.sprout.sandbox.domain;

import app.sprout.sandbox.config.SandboxProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Calls the rest of Sprout on a fictional person's behalf, the way the gateway does for a signed-in
 * customer (their user id in {@code X-User-Id}), or as a Sprout service where that's what's asked.
 * Each call has a hard deadline.
 */
@Component
public class Upstreams {

    static final Duration DEADLINE = Duration.ofSeconds(8);

    /** No answer, or an answer that isn't a decision (5xx). */
    public static class Unreachable extends RuntimeException {
        public Unreachable(String what) {
            super(what);
        }
    }

    public record Reply(int status, JsonNode body) {
        public boolean ok() {
            return status / 100 == 2;
        }

        public String code() {
            return body == null ? "" : body.path("code").asText();
        }
    }

    final SandboxProperties props;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    private final Onward onward;

    public Upstreams(SandboxProperties props, ObjectMapper json, Onward onward) {
        this.props = props;
        this.json = json;
        this.onward = onward;
    }

    /** As the person (X-User-Id), with an idempotency key on writes. */
    public Reply as(UUID user, String method, String url, Object body, String key) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("X-User-Id", user.toString());
        if (key != null) {
            h.put("Idempotency-Key", key);
        }
        return send(method, url, body, h);
    }

    public Reply send(String method, String url, Object body, Map<String, String> headers) {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url)).timeout(DEADLINE);
        headers.forEach(req::header);
        try {
            if (body == null) {
                req.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                req.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            }
            onward.headers(req);
            HttpResponse<String> res = http.sendAsync(req.build(), HttpResponse.BodyHandlers.ofString()).get(DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
            if (res.statusCode() >= 500) {
                throw new Unreachable(url + " answered " + res.statusCode());
            }
            return new Reply(res.statusCode(), res.body() == null || res.body().isBlank() ? null : json.readTree(res.body()));
        } catch (Unreachable e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new Unreachable(url + " unreachable: " + e.getClass().getSimpleName());
        }
    }
}
