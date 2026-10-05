package app.sprout.sandbox;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Sprout Sandbox
 *
 * <p>Runs on its own ({@link #main}) or inside a shared JVM host, which calls {@link #builder()}.
 * Either way it reads {@code sandbox.yml}, never {@code application.yml}, so services sharing a
 * host can't read each other's settings.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class SandboxApplication {

    public static final String CONFIG_NAME = "sandbox";

    public static void main(String[] args) {
        builder().run(args);
    }

    public static SpringApplicationBuilder builder() {
        return new SpringApplicationBuilder(SandboxApplication.class)
                .properties("spring.config.name=" + CONFIG_NAME);
    }
}
