package com.documentplatform.identity.spring;

import com.documentplatform.identity.IdentityProviderType;
import com.documentplatform.identity.inmemory.InMemoryIdentityStore;
import com.documentplatform.identity.signin.DevelopmentLicence;
import com.documentplatform.identity.store.ConnectionOutcome;
import com.documentplatform.identity.store.ConnectionValues;
import com.documentplatform.identity.store.LoginConnectionStore;
import com.documentplatform.identity.store.LoginConnections;
import com.documentplatform.identity.testkit.MockIssuer;
import com.documentplatform.licensing.ClockGuard;
import com.documentplatform.licensing.LastSeenStore;
import com.documentplatform.licensing.LicenceKind;
import com.documentplatform.licensing.LicenceService;
import com.documentplatform.licensing.LicenceStore;
import com.documentplatform.licensing.LicenceVerifier;
import com.documentplatform.licensing.TrustedKeys;
import com.documentplatform.licensing.inmemory.InMemoryLastSeenStore;
import com.documentplatform.licensing.inmemory.InMemoryLicenceStore;
import com.documentplatform.licensing.inmemory.InMemoryUsageLedger;
import com.documentplatform.licensing.inmemory.TestLicences;
import com.documentplatform.licensing.spring.LicensingAutoConfiguration;
import com.documentplatform.licensing.spring.LicensingClock;
import com.documentplatform.licensing.usage.UsageLedger;
import com.documentplatform.notifications.inmemory.InMemorySecretResolver;
import com.documentplatform.notifications.secret.SecretResolver;
import com.documentplatform.outbound.spring.OutboundAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;

import static com.documentplatform.licensing.inmemory.TestLicences.PRODUCT;
import static com.documentplatform.licensing.inmemory.TestLicences.TENANT;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The release's {@code trusted-keys.json} marks a key {@code "development": true}; through licensing-spring and
 * identity-spring a licence signed by that key allows a local http login server, a production-signed one does not
 * (P036, xc-devkey-1: the marks were dropped when licensing-spring merged the keys).
 */
class DevelopmentKeyWiringTest {

    private static final Instant NOW = Instant.parse("2026-11-21T10:00:00Z");

    private final TestLicences development = new TestLicences("dev-key-1");
    private final TestLicences production = new TestLicences("prod-key-1");

    @TempDir
    Path dir;

    private Path keysFile() throws Exception {
        TrustedKeys keys = TrustedKeys.of(Map.of(
                development.keyId(), development.keyPair().getPublic(),
                production.keyId(), production.keyPair().getPublic()), Set.of(development.keyId()));
        Path f = dir.resolve("trusted-keys.json");
        Files.writeString(f, keys.toJson(), StandardCharsets.UTF_8);
        return f;
    }

    /** The draft outcome for a local http issuer with a licence signed by {@code signer} in force. */
    private ConnectionOutcome draftUnder(TestLicences signer) throws Exception {
        Path keys = keysFile();
        InMemoryLicenceStore licenceStore = new InMemoryLicenceStore();
        InMemoryLastSeenStore lastSeen = new InMemoryLastSeenStore();
        InMemoryUsageLedger ledger = new InMemoryUsageLedger();
        // Stored the way the licence command does.
        new LicenceService(licenceStore, new LicenceVerifier(TrustedKeys.parse(Files.readString(keys))),
                new ClockGuard(Clock.fixed(NOW, ZoneOffset.UTC), lastSeen), ledger, PRODUCT, null)
                .store(signer.sign(signer.subscription(LicenceKind.PRODUCTION, LocalDate.parse("2026-01-01"),
                        LocalDate.parse("2027-12-31"))));
        InMemoryIdentityStore identity = new InMemoryIdentityStore();
        identity.saveCustomer(TENANT, "Test Customer");
        ConnectionOutcome[] out = new ConnectionOutcome[1];
        try (MockIssuer issuer = MockIssuer.start()) {
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(LicensingAutoConfiguration.class,
                            OutboundAutoConfiguration.class, IdentityIssuerFetchAutoConfiguration.class,
                            IdentityLoginConnectionStoreAutoConfiguration.class,
                            IdentityLoginConnectionsAutoConfiguration.class))
                    .withClassLoader(new FilteredClassLoader("com.documentplatform.integration"))
                    .withPropertyValues("platform.outbound.installation=ON_PREMISES",
                            "platform.licensing.trusted-keys-file=" + keys,
                            "platform.licensing.product-code=" + PRODUCT, "platform.licensing.tenant-id=" + TENANT)
                    .withBean(SecretResolver.class, InMemorySecretResolver::new)
                    .withBean(LicensingClock.class, () -> new LicensingClock(Clock.fixed(NOW, ZoneOffset.UTC)))
                    .withBean(LicenceStore.class, () -> licenceStore)
                    .withBean(LastSeenStore.class, () -> lastSeen)
                    .withBean(UsageLedger.class, () -> ledger)
                    .withBean(LoginConnectionStore.class, () -> identity)
                    .run(ctx -> {
                        assertNull(ctx.getStartupFailure(), () -> String.valueOf(ctx.getStartupFailure()));
                        assertNotSame(DevelopmentLicence.NONE, ctx.getBean(DevelopmentLicence.class),
                                "the licence decides, not the no-licensing default");
                        assertEquals("http", issuer.issuer().getScheme(), "a local http login server");
                        out[0] = ctx.getBean(LoginConnections.class).createDraft(TENANT, "PRODUCT_A", 0,
                                new ConnectionValues("test-idp", IdentityProviderType.OIDC, issuer.issuer(),
                                        "web-client", "api://product-a", null), "it-admin");
                    });
        }
        return out[0];
    }

    @Test
    void aDevelopmentSignedLicenceAllowsALocalHttpIssuer() throws Exception {
        ConnectionOutcome d = draftUnder(development);
        assertEquals(ConnectionOutcome.Result.DRAFT_CREATED, d.result(), d.message());
    }

    @Test
    void aProductionSignedLicenceStillRefusesALocalHttpIssuer() throws Exception {
        ConnectionOutcome d = draftUnder(production);
        assertEquals(ConnectionOutcome.Result.IDN_HTTP_ISSUER_NOT_ALLOWED, d.result(), d.message());
    }
}
