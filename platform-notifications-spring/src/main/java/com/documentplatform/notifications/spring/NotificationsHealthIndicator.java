package com.documentplatform.notifications.spring;

import com.documentplatform.notifications.ChannelKind;
import com.documentplatform.notifications.DestinationId;
import com.documentplatform.notifications.email.MailServer;
import com.documentplatform.notifications.email.MicrosoftTokens;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Actuator health of notifications ({@code /actuator/health/notifications}): each destination with its state
 * (Working, Having problems, Down, Not used yet), and for a failure its stable code and fixed reason; and under
 * {@code mail} the sign-in mode (PASSWORD or OAUTH2_MICROSOFT) with, for Microsoft 365, the token state. Never an address,
 * a login or a secret.
 *
 * <p>The component reports {@code UP} when no destination has a problem and {@code DEGRADED} otherwise. A broken
 * webhook must not mark the whole app unhealthy (and get it restarted or taken out of a load balancer), so DEGRADED is
 * not counted in the overall status and maps to HTTP 200.
 */
public final class NotificationsHealthIndicator implements HealthIndicator {

    /** Some destination is having problems or is down. */
    public static final Status DEGRADED = new Status("DEGRADED", "A notification destination is having problems or is down");

    private final DestinationHealth health;
    private final MailServer mail;
    private final MicrosoftTokens tokens;

    public NotificationsHealthIndicator(DestinationHealth health) {
        this(health, null, null);
    }

    /**
     * @param mail   the configured mail server, or null without e-mail; its sign-in mode is shown
     * @param tokens Microsoft 365 tokens, whose state (never the token) is shown with auth OAUTH2_MICROSOFT
     */
    public NotificationsHealthIndicator(DestinationHealth health, MailServer mail, MicrosoftTokens tokens) {
        this.health = health;
        this.mail = mail;
        this.tokens = tokens;
    }

    @Override
    public Health health() {
        Map<String, Object> destinations = new LinkedHashMap<>();
        boolean problem = false;
        for (Map.Entry<DestinationId, DestinationHealth.Status> e : health.statuses().entrySet()) {
            DestinationHealth.Status s = e.getValue();
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("channel", s.channel().displayName());
            d.put("state", s.state().label());
            if (s.failure() != null) {
                d.put("code", s.failure().name());
                d.put("reason", s.reason().orElseThrow());
                d.put("failingSince", s.failingSince().toString());
            }
            if (s.lastAttempt() != null) {
                d.put("lastAttempt", s.lastAttempt().toString());
            }
            destinations.put(e.getKey().value(), d);
            problem |= s.state() == DestinationHealth.State.HAVING_PROBLEMS || s.state() == DestinationHealth.State.DOWN;
        }
        Health.Builder h = Health.status(problem ? DEGRADED : Status.UP).withDetail("destinations", destinations);
        if (mail != null) {
            h.withDetail("mail", mailSignIn());
        }
        return h.build();
    }

    /** The sign-in mode and, for Microsoft 365, the token state: codes, labels and times only. */
    private Map<String, Object> mailSignIn() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("auth", mail.auth().name());
        if (mail.oauth() != null && tokens != null) {
            MicrosoftTokens.Status s = tokens.status(mail.oauth());
            m.put("signIn", s.state().label());
            if (s.failure() != null) {
                m.put("code", s.failure().name());
                m.put("reason", s.failure().describe(ChannelKind.EMAIL));
            }
            if (s.lastFetch() != null) m.put("lastTokenFetch", s.lastFetch().toString());
            if (s.expiresAt() != null) m.put("tokenExpiresAt", s.expiresAt().toString());
        }
        return m;
    }
}
