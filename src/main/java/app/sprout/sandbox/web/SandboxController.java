package app.sprout.sandbox.web;

import app.sprout.sandbox.domain.Demo;
import app.sprout.sandbox.domain.Demo.Started;
import app.sprout.sandbox.domain.Demo.Status;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The sandbox API (sandbox-v3.yaml): whether a demo account is ready, and trying Sprout with one. Public. */
@RestController
public class SandboxController {

    public record StartRequest(String name) {}

    private final Demo demo;

    public SandboxController(Demo demo) {
        this.demo = demo;
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
        return ResponseEntity.status(HttpStatus.CREATED).body(m);
    }
}
