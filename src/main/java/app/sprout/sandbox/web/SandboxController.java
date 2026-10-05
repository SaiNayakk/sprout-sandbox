package app.sprout.sandbox.web;

import app.sprout.sandbox.domain.Demo;
import app.sprout.sandbox.domain.Demo.Started;
import app.sprout.sandbox.domain.Personas;
import app.sprout.sandbox.domain.Personas.Group;
import app.sprout.sandbox.domain.Personas.Persona;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The sandbox API (sandbox-v1.yaml): who visitors can explore as, and starting as one of them. Public. */
@RestController
public class SandboxController {

    public record StartRequest(String group) {}

    private final Demo demo;

    public SandboxController(Demo demo) {
        this.demo = demo;
    }

    @GetMapping("/v1/personas")
    public Map<String, Object> personas() {
        List<Map<String, Object>> groups = new ArrayList<>();
        for (Group g : Group.values()) {
            groups.add(Map.of("code", g.name(), "label", Personas.label(g)));
        }
        groups.add(Map.of("code", "ANY", "label", "No preference"));
        return Map.of("groups", groups, "personas", Personas.ALL.stream().map(SandboxController::persona).toList());
    }

    @PostMapping("/v1/demo-sessions")
    public ResponseEntity<Map<String, Object>> start(@RequestBody StartRequest req) {
        Started s = demo.start(req.group());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("persona", persona(s.persona()));
        m.put("accessToken", s.tokens().path("accessToken").asText());
        m.put("tokenType", "Bearer");
        m.put("expiresIn", s.tokens().path("expiresIn").asInt());
        m.put("refreshToken", s.tokens().path("refreshToken").asText());
        return ResponseEntity.status(HttpStatus.CREATED).body(m);
    }

    static Map<String, Object> persona(Persona p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.id());
        m.put("name", p.name());
        m.put("pronouns", p.pronouns());
        m.put("group", p.group().name());
        m.put("city", p.city());
        m.put("story", p.story());
        m.put("style", p.style().name());
        return m;
    }
}
