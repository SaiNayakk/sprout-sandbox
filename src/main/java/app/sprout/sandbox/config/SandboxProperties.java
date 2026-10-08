package app.sprout.sandbox.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings under {@code sprout.sandbox} in sandbox.yml. {@code warm} is how many demo accounts are kept
 * ready for the next visitors; {@code keepFor} is how long a visitor keeps theirs.
 */
@ConfigurationProperties("sprout.sandbox")
public record SandboxProperties(Duration every, int warm, Duration keepFor, String serviceKey, String upiPin, String emailDomain,
                                String payrollKey, String identityUrl, String marketdataUrl, String accountsUrl, String paymentsUrl,
                                String bankUrl, String omsUrl, String plansUrl, String habitsUrl, String goalsUrl, String rewardsUrl) {}
