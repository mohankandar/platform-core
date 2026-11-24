package com.documentplatform.licensing.spring;

import com.documentplatform.licensing.ClockGuard;
import com.documentplatform.licensing.Ed25519;
import com.documentplatform.licensing.Licence;
import com.documentplatform.licensing.LicenceKind;
import com.documentplatform.licensing.LicenceService;
import com.documentplatform.licensing.LicenceVerifier;
import com.documentplatform.licensing.inmemory.TestLicences;
import com.documentplatform.licensing.jdbc.PostgresLastSeenStore;
import com.documentplatform.licensing.jdbc.PostgresLicenceStore;
import com.documentplatform.licensing.jdbc.PostgresUsageLedger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.health.HealthContributorAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.health.HealthEndpointAutoConfiguration;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.HttpCodeStatusMapper;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static com.documentplatform.licensing.inmemory.TestLicences.PRODUCT;
import static com.documentplatform.licensing.inmemory.TestLicences.TENANT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * licensing-92: with Actuator's own health endpoint (the auto-configuration an app has), the licence warnings
 * {@code LICENCE_WARNING} and {@code INTAKE_STOPPED} are left out of the overall status: overall UP, HTTP 200, also for
 * {@code /actuator/health/licensing}. An edited stored row while running is DOWN: overall DOWN, HTTP 503.
 */
class LicensingActuatorHealthTest {

    private static final Instant NOW = Instant.parse("2026-11-21T10:00:00Z");
    private final TestLicences licences = new TestLicences();

    private ApplicationContextRunner app(DataSource ds, Instant now) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(LicensingAutoConfiguration.class,
                        HealthContributorAutoConfiguration.class, HealthEndpointAutoConfiguration.class))
                .withBean(DataSource.class, () -> ds)
                .withBean(LicensingClock.class, () -> new LicensingClock(Clock.fixed(now, ZoneOffset.UTC)))
                .withBean(com.documentplatform.licensing.LicenceBuild.class, licences::licenceBuild)
                .withPropertyValues("platform.licensing.product-code=" + PRODUCT, "platform.licensing.tenant-id=" + TENANT);
    }

    private void store(DataSource ds, String starts, String expires) {
        Licence l = licences.subscription(LicenceKind.PRODUCTION, LocalDate.parse(starts), LocalDate.parse(expires));
        new LicenceService(new PostgresLicenceStore(ds), new LicenceVerifier(licences.trustedKeys()),
                new ClockGuard(Clock.fixed(NOW, ZoneOffset.UTC), new PostgresLastSeenStore(ds)),
                new PostgresUsageLedger(ds), PRODUCT, null).store(licences.sign(l));
    }

    /** Overall and licensing status, and the HTTP code Actuator answers for each. */
    private record Seen(Status overall, int overallHttp, Status licensing, int licensingHttp) {
    }

    private static Seen seen(AssertableApplicationContext ctx) {
        assertNull(ctx.getStartupFailure());
        HealthEndpoint endpoint = ctx.getBean(HealthEndpoint.class);
        HttpCodeStatusMapper http = ctx.getBean(HttpCodeStatusMapper.class);
        HealthComponent overall = endpoint.health();
        HealthComponent licensing = endpoint.healthForPath("licensing");
        return new Seen(overall.getStatus(), http.getStatusCode(overall.getStatus()), licensing.getStatus(),
                http.getStatusCode(licensing.getStatus()));
    }

    @Test
    void graceAndGraceOverStayUpWithHttp200AndAnEditedRowIsDown() {
        Db db = Db.migrated();
        DataSource ds = db.dmlUser();
        store(ds, "2026-01-01", "2026-11-11");

        app(ds, NOW).run(ctx -> assertEquals(new Seen(Status.UP, 200, LicensingHealthIndicator.WARNING, 200),
                seen(ctx), "in grace"));
        app(ds, Instant.parse("2026-12-12T00:00:00Z")).run(ctx -> assertEquals(
                new Seen(Status.UP, 200, LicensingHealthIndicator.INTAKE_STOPPED, 200), seen(ctx), "grace over"));

        app(ds, NOW).run(ctx -> {
            assertEquals(Status.UP, seen(ctx).overall());
            db.exec("update lic_licence set file = replace(file, '\"amount\": 1000', '\"amount\": 9000')");
            assertEquals(new Seen(Status.DOWN, 503, Status.DOWN, 503), seen(ctx), "edited row while running");
        });
    }

    @Test
    void aValidLicenceIsUp() {
        Db db = Db.migrated();
        DataSource ds = db.dmlUser();
        store(ds, "2026-01-01", "2027-12-31");
        app(ds, NOW).run(ctx -> assertEquals(new Seen(Status.UP, 200, Status.UP, 200), seen(ctx)));
    }
}
