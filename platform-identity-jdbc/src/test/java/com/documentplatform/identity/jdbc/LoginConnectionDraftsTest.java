package com.documentplatform.identity.jdbc;

import com.documentplatform.identity.IdentityConnectionStatus;
import com.documentplatform.identity.IdentityProviderType;
import com.documentplatform.identity.signin.DevelopmentLicence;
import com.documentplatform.identity.signin.OidcTestSignIn;
import com.documentplatform.identity.signin.TestReport;
import com.documentplatform.identity.signin.TestSignIn;
import com.documentplatform.identity.store.ChangeSource;
import com.documentplatform.identity.store.ConnectionOutcome;
import com.documentplatform.identity.store.ConnectionValues;
import com.documentplatform.identity.store.LoginConnection;
import com.documentplatform.identity.store.LoginConnections;
import com.documentplatform.identity.testkit.MockIssuer;
import com.documentplatform.licensing.ClockGuard;
import com.documentplatform.licensing.Licence;
import com.documentplatform.licensing.LicenceKind;
import com.documentplatform.licensing.LicenceService;
import com.documentplatform.licensing.LicenceVerifier;
import com.documentplatform.licensing.TrustedKeys;
import com.documentplatform.licensing.inmemory.InMemoryLastSeenStore;
import com.documentplatform.licensing.inmemory.InMemoryLicenceStore;
import com.documentplatform.licensing.inmemory.InMemoryUsageLedger;
import com.documentplatform.licensing.inmemory.TestLicences;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.documentplatform.identity.store.ConnectionOutcome.Result.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * P031 B1 on Postgres, as the DML-only app user, against the testkit's mock issuer: drafts, test sign-in, activate,
 * rollback, history, the one-draft and one-active rules in the database, and the http issuer rule from the licence.
 */
class LoginConnectionDraftsTest {

    static final String TENANT = "TEN-TEST-0001";
    static final String A = "PRODUCT_A";
    static final String AUD = "api://product-a";
    static final URI HTTPS = URI.create("https://login.example.test/oauth2/default");

    final MockIssuer issuer = MockIssuer.start();

    @AfterEach
    void stop() {
        issuer.close();
    }

    static DataSource customer(Pg db) {
        DataSource app = db.appUser(PostgresIdentityStoresTest.DML);
        new PostgresEntityStore(app).saveCustomer(TENANT, "Test Customer");
        return app;
    }

    static ConnectionValues values(URI iss, String audience) {
        return new ConnectionValues("test-idp", IdentityProviderType.OIDC, iss, "web-client", audience, null);
    }

    LoginConnections service(DataSource app, DevelopmentLicence licence) {
        return new LoginConnections(new PostgresLoginConnectionStore(app),
                new OidcTestSignIn(MockIssuer.fetch(), Clock.systemUTC()), licence);
    }

    @Test
    void theWholeFlowOnPostgresWithHistory() {
        Pg db = Pg.migrated();
        DataSource app = customer(db);
        PostgresLoginConnectionStore store = new PostgresLoginConnectionStore(app);
        LoginConnections c = service(app, () -> true);

        ConnectionOutcome d = c.createDraft(TENANT, A, 0, values(issuer.issuer(), AUD), "it-admin");
        assertEquals(DRAFT_CREATED, d.result());
        UUID first = d.connection().connectionId();
        ConnectionOutcome v = c.validate(first, 1, TestSignIn.withToken(issuer.token(AUD, List.of("g"),
                Duration.ofMinutes(5))), "it-admin");
        assertEquals(VALIDATED, v.result(), v.message());
        assertTrue(v.test().tokenChecked());
        assertEquals(ACTIVATED, c.activate(first, 2, "it-admin").result());

        ConnectionOutcome copy = c.createDraft(TENANT, A, 3, null, "it-admin");
        UUID second = copy.connection().connectionId();
        assertEquals(IDN_DRAFT_EXISTS, c.createDraft(TENANT, A, 3, null, "other-admin").result());
        assertEquals(DRAFT_UPDATED, c.updateDraft(second, 1, values(issuer.issuer(), "api://v2"), "it-admin").result());
        assertEquals(IDN_NOT_VALIDATED, c.activate(second, 2, "it-admin").result());
        assertEquals(VALIDATED, c.validate(second, 2, TestSignIn.issuerOnly(), "it-admin").result());
        assertEquals(ACTIVATED, c.activate(second, 3, "it-admin").result());

        assertEquals(second, store.active(TENANT, A).orElseThrow().connectionId());
        assertEquals(IdentityConnectionStatus.DISABLED, store.find(first).orElseThrow().status());
        assertEquals(1, db.count("select count(*) from idn_login_connection where status = 'ACTIVE'"));

        ConnectionOutcome back = c.rollback(TENANT, A, "it-admin");
        assertEquals(ROLLED_BACK, back.result());
        assertEquals(first, back.connection().connectionId());
        assertEquals(AUD, store.active(TENANT, A).orElseThrow().apiAudience());
        assertEquals(IdentityConnectionStatus.DISABLED, store.find(second).orElseThrow().status());

        // History rows: who, source and every status, in the database.
        assertEquals(List.of("DRAFT", "VALIDATED", "ACTIVE", "DISABLED", "ACTIVE"), db.strings(
                "select status from idn_login_connection_history where connection_id = '" + first + "' "
                        + "order by configuration_version"));
        assertEquals(0, db.count("select count(*) from idn_login_connection_history where change_source <> 'APP' "
                + "or changed_by <> 'it-admin'"));
    }

    @Test
    void theDatabaseAllowsOneDraftPerCustomerAndProduct() {
        Pg db = Pg.migrated();
        DataSource app = customer(db);
        PostgresLoginConnectionStore store = new PostgresLoginConnectionStore(app);
        store.insert(draft(HTTPS), ChangeSource.APP, "a");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> store.insert(draft(HTTPS), ChangeSource.APP, "b"));
        assertTrue(e.getMessage().contains("DRAFT or VALIDATED"));
        // Even past the store's own check, the index refuses a second draft.
        db.exec("insert into idn_login_connection (connection_id, tenant_id, product_code, provider_id, provider_type, "
                + "issuer_uri, api_audience, status, configuration_version) values ('" + UUID.randomUUID() + "', '"
                + TENANT + "', 'PRODUCT_B', 'p', 'OIDC', 'https://x.example.test/', 'a', 'DRAFT', 1)");
        assertThrows(IllegalStateException.class, () -> db.exec("insert into idn_login_connection (connection_id, "
                + "tenant_id, product_code, provider_id, provider_type, issuer_uri, api_audience, status, "
                + "configuration_version) values ('" + UUID.randomUUID() + "', '" + TENANT + "', 'PRODUCT_B', 'p', "
                + "'OIDC', 'https://x.example.test/', 'a', 'VALIDATED', 1)"));
    }

    @Test
    void aRaceOnTheSameVersionIsAConflictAndNothingIsWritten() {
        Pg db = Pg.migrated();
        DataSource app = customer(db);
        LoginConnections c = service(app, DevelopmentLicence.NONE);
        UUID id = c.createDraft(TENANT, A, 0, values(HTTPS, AUD), "a").connection().connectionId();
        assertEquals(DRAFT_UPDATED, c.updateDraft(id, 1, values(HTTPS, "api://one"), "a").result());
        assertEquals(IDN_VERSION_CONFLICT, c.updateDraft(id, 1, values(HTTPS, "api://two"), "b").result());
        assertEquals("api://one", new PostgresLoginConnectionStore(app).find(id).orElseThrow().apiAudience());
        assertEquals(2, db.count("select count(*) from idn_login_connection_history"));
    }

    @Test
    void anHttpIssuerFollowsTheLicencesSigningKey() {
        TestLicences dev = new TestLicences("dev-key-1");
        TestLicences prod = new TestLicences("lic-key-1");
        Pg db = Pg.migrated();
        DataSource app = customer(db);
        TrustedKeys keys = TrustedKeys.of(Map.of("dev-key-1", dev.keyPair().getPublic(),
                "lic-key-1", prod.keyPair().getPublic()), Set.of("dev-key-1"));

        LicenceService devService = service(keys, dev);
        assertTrue(LicenceSigningKey.of(devService, keys).signedByDevelopmentKey());
        LicenceService prodService = service(keys, prod);
        assertFalse(LicenceSigningKey.of(prodService, keys).signedByDevelopmentKey());
        LicenceService none = new LicenceService(new InMemoryLicenceStore(), new LicenceVerifier(keys),
                new ClockGuard(Clock.systemUTC(), Duration.ofHours(1), new InMemoryLastSeenStore()),
                new InMemoryUsageLedger(), A, null);
        assertFalse(LicenceSigningKey.of(none, keys).signedByDevelopmentKey(), "no licence: false");

        assertEquals(IDN_HTTP_ISSUER_NOT_ALLOWED, service(app, LicenceSigningKey.of(prodService, keys))
                .createDraft(TENANT, A, 0, values(issuer.issuer(), AUD), "a").result());
        LoginConnections devConnections = service(app, LicenceSigningKey.of(devService, keys));
        ConnectionOutcome d = devConnections.createDraft(TENANT, A, 0, values(issuer.issuer(), AUD), "a");
        assertEquals(DRAFT_CREATED, d.result());
        ConnectionOutcome failed = devConnections.validate(d.connection().connectionId(), 1,
                TestSignIn.withToken("not-a-token"), "a");
        assertEquals(TestReport.Code.IDN_TEST_TOKEN_UNREADABLE, failed.test().failure());
    }

    static LicenceService service(TrustedKeys keys, TestLicences licences) {
        LicenceService s = new LicenceService(new InMemoryLicenceStore(), new LicenceVerifier(keys),
                new ClockGuard(Clock.systemUTC(), Duration.ofHours(1), new InMemoryLastSeenStore()),
                new InMemoryUsageLedger(), TestLicences.PRODUCT, null);
        Licence l = licences.subscription(LicenceKind.NON_PRODUCTION, LocalDate.parse("2026-01-01"),
                LocalDate.parse("2030-12-31"));
        s.store(licences.sign(l));
        return s;
    }

    static LoginConnection draft(URI iss) {
        return new LoginConnection(UUID.randomUUID(), TENANT, A, "test-idp", IdentityProviderType.OIDC, iss, null, AUD,
                null, IdentityConnectionStatus.DRAFT, 1);
    }
}
