package app.sprout.sandbox.web;

import app.sprout.sandbox.domain.ApiException;
import app.sprout.sandbox.domain.Demo;
import app.sprout.sandbox.domain.Demo.Started;
import app.sprout.sandbox.domain.Demo.Status;
import app.sprout.sandbox.domain.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The sandbox API (sandbox-v3.yaml): whether a demo account is ready, trying Sprout with one, and keeping it while there. Public. */
@RestController
public class SandboxController {

    public record StartRequest(String name) {}

    private final Demo demo;
    private final ObjectMapper json;

    public SandboxController(Demo demo, ObjectMapper json) {
        this.demo = demo;
        this.json = json;
    }

    @GetMapping("/v3/demo")
    public Map<String, Object> status() {
        Status s = demo.status();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ready", s.ready());
        if (s.sessionsLived() != null) {
            m.put("sessionsLived", s.sessionsLived());
        }
        m.put("endsAfterMinutes", s.endsAfterMinutes());
        return m;
    }

    @PostMapping("/v3/demo-sessions")
    public ResponseEntity<Map<String, Object>> start(@RequestBody StartRequest req) {
        Started s = demo.start(req.name());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", s.name());
        m.put("sessionsLived", s.sessionsLived());
        m.put("endsAt", s.endsAt().toString());
        m.put("accessToken", s.tokens().path("accessToken").asText());
        m.put("tokenType", "Bearer");
        m.put("expiresIn", s.tokens().path("expiresIn").asInt());
        m.put("refreshToken", s.tokens().path("refreshToken").asText());
        m.put("demoSession", s.demoSession());
        return ResponseEntity.status(HttpStatus.CREATED).body(m);
    }

    public record Presence(String demoSession, Boolean active) {}

    @PostMapping("/v3/demo-sessions/heartbeat")
    public ResponseEntity<Void> heartbeat(@RequestBody Presence p) {
        demo.heartbeat(p.demoSession(), Boolean.TRUE.equals(p.active()));
        return ResponseEntity.noContent().build();
    }

    /** sendBeacon posts the JSON as text/plain, so both are taken. */
    @PostMapping(path = "/v3/demo-sessions/leave", consumes = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_PLAIN_VALUE})
    public ResponseEntity<Void> leave(@RequestBody String body) {
        Presence p;
        try {
            p = json.readValue(body, Presence.class);
        } catch (JsonProcessingException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Send {\"demoSession\": \"...\"}.");
        }
        demo.leave(p.demoSession());
        return ResponseEntity.noContent().build();
    }
}
