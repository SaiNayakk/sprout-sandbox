package app.sprout.sandbox.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings under {@code sprout.sandbox} in sandbox.yml. {@code warm} is how many demo accounts are kept
 * ready for the next visitors and {@code most} how many may be open at once (given out or waiting); {@code keepFor}
 * is the longest a visitor keeps theirs, {@code goneAfter} how long without a heartbeat and {@code idleAfter} how long
 * without anything done before it is closed; {@code history} is how many sessions every account lives before a
 * visitor gets it, so every visitor starts from the same history.
 */
@ConfigurationProperties("sprout.sandbox")
public record SandboxProperties(Duration every, int warm, int most, int history, Duration keepFor, Duration goneAfter, Duration idleAfter, String serviceKey, String upiPin, String emailDomain,
                                String payrollKey, String identityUrl, String marketdataUrl, String accountsUrl, String paymentsUrl,
                                String bankUrl, String omsUrl, String plansUrl, String habitsUrl, String goalsUrl, String rewardsUrl) {}
