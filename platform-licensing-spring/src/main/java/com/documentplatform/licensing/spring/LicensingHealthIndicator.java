package com.documentplatform.licensing.spring;

import com.documentplatform.licensing.ClockReading;
import com.documentplatform.licensing.Licence;
import com.documentplatform.licensing.LicenceException;
import com.documentplatform.licensing.LicenceService;
import com.documentplatform.licensing.LicenceState;
import com.documentplatform.licensing.LicenceStatus;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Actuator health of licensing ({@code /actuator/health/licensing}): state, licence ID and version, kind and the
 * test-environment flag, expiry or updates-until, days to expiry, grace end, intake allowed, product licensed, and the
 * clock flag with how far behind. Never keys, signatures or the licence file.
 *
 * <p>{@code UP} when all is well; {@link #WARNING} (expiring, in grace, not started, clock behind) and
 * {@link #INTAKE_STOPPED} (grace over, product not licensed) are not counted in the overall status and map to HTTP 200,
 * so a licence matter never gets the app restarted or taken out of a load balancer. {@code DOWN} only when the stored
 * licence is refused while running (edited row), with its code.
 */
final class LicensingHealthIndicator implements HealthIndicator {

    public static final Status WARNING = new Status("LICENCE_WARNING", "The licence needs attention");
    public static final Status INTAKE_STOPPED = new Status("INTAKE_STOPPED", "Intake is stopped by the licence");

    private final LicenceService service;

    public LicensingHealthIndicator(LicenceService service) {
        this.service = service;
    }

    @Override
    public Health health() {
        LicenceStatus s;
        try {
            s = service.status();
        } catch (LicenceException e) {
            return Health.down().withDetail("code", e.code().name()).withDetail("reason", e.code().meaning())
                    .withDetail("action", e.code().action()).build();
        }
        Licence l = s.licence();
        boolean product = service.productLicensed(s);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("state", s.state().name());
        d.put("licenceId", l.licenceId());
        d.put("version", l.version());
        d.put("type", l.type().name());
        d.put("kind", l.kind().name());
        d.put("testEnvironment", l.testEnvironment());
        l.expiry().ifPresent(x -> d.put("expiresOn", x.toString()));
        l.updatesUntilDate().ifPresent(x -> d.put("updatesUntil", x.toString()));
        s.daysUntilExpiry().ifPresent(x -> d.put("daysUntilExpiry", x));
        if (s.state() == LicenceState.EXPIRED_IN_GRACE || s.state() == LicenceState.EXPIRED_GRACE_OVER) {
            d.put("graceEndsOn", s.graceEndsOn().orElseThrow().toString());
        }
        d.put("intakeAllowed", s.intakeAllowed() && product);
        d.put("productLicensed", product);
        d.put("clockBehind", s.clockBehind());
        if (s.clockBehind()) {
            d.put("clockBehindBy", ClockReading.describe(s.clock().behind()));
        }
        s.renewalWaiting().ifPresent(w -> d.put("renewalStartsOn", w.startsOn().toString()));
        d.put("message", s.message());
        Status status;
        if (!s.intakeAllowed() && s.state() != LicenceState.NOT_STARTED || !product) {
            status = INTAKE_STOPPED;
        } else if (s.state() != LicenceState.VALID || s.clockBehind()) {
            status = WARNING;
        } else {
            status = Status.UP;
        }
        return Health.status(status).withDetails(d).build();
    }
}
