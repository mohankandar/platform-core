package com.documentplatform.identity.spring;

import com.documentplatform.identity.IdentityConnectionStatus;
import com.documentplatform.identity.IdentityProviderType;
import com.documentplatform.identity.inmemory.InMemoryIdentityStore;
import com.documentplatform.identity.jdbc.IdentitySchemaException;
import com.documentplatform.identity.push.PushCaller;
import com.documentplatform.identity.push.PushCallerDirectory;
import com.documentplatform.identity.push.PushCallerIdentity;
import com.documentplatform.identity.push.PushCallerInput;
import com.documentplatform.identity.push.PushCallerResult;
import com.documentplatform.identity.push.PushRefusal;
import com.documentplatform.identity.store.ChangeSource;
import com.documentplatform.identity.store.LoginConnection;
import com.documentplatform.identity.store.MachineBinding;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.jwt.Jwt;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** P028 B2: the PushCaller bean from platform.identity.push.*, and the helper for a Jwt and a client certificate. */
class PushCallerWiringTest {

    static final String TENANT = "TEN-TEST-0001";
    static final String SLOT = "ENT-TEST-0001";
    static final URI ISSUER = URI.create("https://login.example.test/oauth2/default");
    static final String AUD = "api://product-a";

    /** A throwaway self-signed certificate (public part only). */
    static final X509Certificate CERT = cert("""
            -----BEGIN CERTIFICATE-----
            MIIBXDCCAQOgAwIBAgIITuLajl3FqYYwCgYIKoZIzj0EAwMwIjEgMB4GA1UEAxMX
            cHVzaC1jYWxsZXItb25lLmV4YW1wbGUwIBcNMjYxMTIzMDMzMTMwWhgPMjEyNjEw
            MzAwMzMxMzBaMCIxIDAeBgNVBAMTF3B1c2gtY2FsbGVyLW9uZS5leGFtcGxlMFkw
            EwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEJYaEyQLU7IW6ruTT6v6JPlciHc/1JQqD
            HlgOXLUhGayvbthK9MGt6kWRNIDJ1eEAd/DqyMEnNZ2Ij01arwg296MhMB8wHQYD
            VR0OBBYEFBFqXq7X/UUppYLy+Xx2WYCwaeePMAoGCCqGSM49BAMDA0cAMEQCIESR
            GicwCm+X79wdIz+mc9YaWuXFh7xZfnn7FrAqYIAMAiAWtSFXSr4V9JJVJ6cFJQMw
            RmeC7Vr7EUnNa2G5tKkz5A==
            -----END CERTIFICATE-----
            """);

    private static X509Certificate cert(String pem) {
        try {
            return (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static InMemoryIdentityStore store() {
        InMemoryIdentityStore s = new InMemoryIdentityStore();
        s.saveCustomer(TENANT, "Test Customer");
        s.syncSlots(TENANT, List.of(SLOT));
        s.activate(SLOT, "Alpha", "admin-1", Instant.parse("2026-11-21T10:00:00Z"));
        s.insert(new LoginConnection(UUID.randomUUID(), TENANT, "PRODUCT_A", "test-idp", IdentityProviderType.OIDC,
                ISSUER, "product-a-web", AUD, null, IdentityConnectionStatus.ACTIVE, 1), ChangeSource.SETUP, "setup");
        s.insertBinding(MachineBinding.create(TENANT, "PRODUCT_A", "0oaSender01", SLOT, "sender-a", null),
                ChangeSource.SETUP, "setup");
        s.insertBinding(MachineBinding.create(TENANT, "PRODUCT_A", PushCaller.fingerprint(CERT), SLOT, "sender-a",
                null), ChangeSource.SETUP, "setup");
        return s;
    }

    private static ApplicationContextRunner runner(String... properties) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(IdentityPushCallerAutoConfiguration.class))
                .withPropertyValues(properties);
    }

    private static Jwt jwt(String clientClaim, String clientId) {
        return Jwt.withTokenValue("not-a-real-token").header("alg", "RS256").issuer(ISSUER.toString())
                .audience(List.of(AUD, "other-audience")).claim(clientClaim, clientId)
                .claim("delivery_entity", SLOT).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300))
                .build();
    }

    @Test
    void theBeanFromTheSettingsAndTheHelperForATokenAndACertificate() {
        InMemoryIdentityStore s = store();
        runner("platform.identity.push.product-code=PRODUCT_A", "platform.identity.push.client-id-claims=appid,azp",
                "platform.identity.push.entity-claim=delivery_entity", "platform.identity.push.cache-for=5s")
                .withBean(PushCallerDirectory.class, s::pushCallerDirectory)
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure(), () -> String.valueOf(ctx.getStartupFailure()));
                    PushCaller pc = ctx.getBean(PushCaller.class);
                    assertEquals(List.of("appid", "azp"), pc.settings().clientIdClaims());
                    assertEquals("delivery_entity", pc.settings().entityClaim());
                    assertEquals(Duration.ofSeconds(5), pc.settings().cacheFor());

                    PushCallerResult byToken = pc.check(PushCallerRequests.input(jwt("azp", "0oaSender01")));
                    assertTrue(byToken.allowed(), byToken.toString());
                    assertEquals(SLOT, byToken.identity().slotId());
                    assertEquals(PushCallerIdentity.Via.TOKEN, byToken.identity().via());
                    assertEquals(PushRefusal.IDN_PUSH_NO_CLIENT_ID,
                            pc.check(PushCallerRequests.input(jwt("client_id", "0oaSender01"))).refusal(),
                            "only the configured claims are read");

                    X509Certificate fromRequest = PushCallerRequests.certificate(new X509Certificate[]{CERT})
                            .orElseThrow();
                    PushCallerResult byCert = pc.check(PushCallerRequests.input(null, fromRequest));
                    assertTrue(byCert.allowed(), byCert.toString());
                    assertEquals(PushCallerIdentity.Via.CERTIFICATE, byCert.identity().via());
                    PushCallerResult both = pc.check(PushCallerRequests.input(jwt("azp", "0oaSender01"), CERT));
                    assertEquals(PushCallerIdentity.Via.TOKEN_AND_CERTIFICATE, both.identity().via());

                    PushCallerInput in = PushCallerRequests.input(jwt("azp", "0oaSender01"));
                    assertEquals(ISSUER, in.issuer());
                    assertEquals(List.of(AUD, "other-audience"), in.audiences());
                    assertFalse(in.toString().contains("0oaSender01"));
                });
        assertTrue(PushCallerRequests.certificate(null).isEmpty());
        assertTrue(PushCallerRequests.certificate(new X509Certificate[0]).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> PushCallerRequests.input(null, null));
    }

    @Test
    void withoutAProductCodeThereIsNoBeanAndBadSettingsStopTheApp() {
        runner().withBean(PushCallerDirectory.class, () -> store().pushCallerDirectory())
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure());
                    assertTrue(ctx.getBeansOfType(PushCaller.class).isEmpty());
                });
        runner("platform.identity.push.product-code=product a")
                .withBean(PushCallerDirectory.class, () -> store().pushCallerDirectory())
                .run(ctx -> assertTrue(String.valueOf(ctx.getStartupFailure()).contains("IDENTITY_PUSH_SETTINGS_INVALID")));
        runner("platform.identity.push.product-code=PRODUCT_A", "platform.identity.push.cache-for=1h")
                .withBean(PushCallerDirectory.class, () -> store().pushCallerDirectory())
                .run(ctx -> assertTrue(String.valueOf(ctx.getStartupFailure()).contains("cacheFor must be 0 to 10")));
        runner("platform.identity.push.product-code=PRODUCT_A")
                .run(ctx -> assertTrue(String.valueOf(ctx.getStartupFailure()).contains("no PushCallerDirectory")));
    }

    @Test
    void onTheAppsDataSourceTheIdentityTablesAreCheckedFirst() {
        try (PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16-alpine")) {
            pg.start();
            PGSimpleDataSource ds = new PGSimpleDataSource();
            ds.setURL(pg.getJdbcUrl());
            ds.setUser(pg.getUsername());
            ds.setPassword(pg.getPassword());
            runner("platform.identity.push.product-code=PRODUCT_A").withBean(DataSource.class, () -> ds).run(ctx -> {
                Throwable t = ctx.getStartupFailure();
                while (t != null && !(t instanceof IdentitySchemaException)) t = t.getCause();
                assertInstanceOf(IdentitySchemaException.class, t, "the schema step has not run: the app does not start");
            });
        }
    }
}
