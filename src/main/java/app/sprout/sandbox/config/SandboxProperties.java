package app.sprout.sandbox.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings under {@code sprout.sandbox} in sandbox.yml. */
@ConfigurationProperties("sprout.sandbox")
public record SandboxProperties(Duration every, String serviceKey, String upiPin, String emailDomain, String payrollKey, String identityUrl,
                                String marketdataUrl, String accountsUrl, String paymentsUrl, String bankUrl, String omsUrl, String plansUrl,
                                String habitsUrl, String goalsUrl, String rewardsUrl) {}
