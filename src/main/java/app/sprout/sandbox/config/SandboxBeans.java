package app.sprout.sandbox.config;

import app.sprout.sandbox.domain.Life;
import app.sprout.sandbox.domain.Setup;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Configuration(proxyBeanMethods = false)
public class SandboxBeans {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Each round: set the people up a step further, then let those ready live the market's new session. */
    @Component
    static class Rounds {

        private static final Logger log = LoggerFactory.getLogger(Rounds.class);

        private final Setup setup;
        private final Life life;

        Rounds(Setup setup, Life life) {
            this.setup = setup;
            this.life = life;
        }

        @Scheduled(fixedDelayString = "${sprout.sandbox.every:5s}", initialDelayString = "${sprout.sandbox.every:5s}")
        void round() {
            try {
                setup.round();
                life.round();
            } catch (RuntimeException e) {
                log.warn("Sandbox round didn't finish: {}", e.getMessage());
            }
        }
    }
}
