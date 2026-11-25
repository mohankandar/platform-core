package com.documentplatform.identity.inmemory;

import com.documentplatform.identity.IdentityConnectionStatus;
import com.documentplatform.identity.IdentityProviderType;
import com.documentplatform.identity.signin.DevelopmentLicence;
import com.documentplatform.identity.signin.OidcTestSignIn;
import com.documentplatform.identity.signin.TestReport;
import com.documentplatform.identity.signin.TestSignIn;
import com.documentplatform.identity.store.ChangeSource;
import com.documentplatform.identity.store.ConnectionChange;
import com.documentplatform.identity.store.ConnectionOutcome;
import com.documentplatform.identity.store.ConnectionValues;
import com.documentplatform.identity.store.LoginConnection;
import com.documentplatform.identity.store.LoginConnections;
import com.documentplatform.identity.testkit.MockIssuer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static com.documentplatform.identity.store.ConnectionOutcome.Result.*;
import static org.junit.jupiter.api.Assertions.*;

/** P031 B1 on the in-memory store, against the testkit's mock issuer (a local test issuer). */
class InMemoryLoginConnectionsTest {

    static final String TENANT = "TEN-TEST-0001";
    static final String A = "PRODUCT_A";
    static final String AUD = "api://product-a";
    static final URI HTTPS = URI.create("https://login.example.test/oauth2/default");

    final MockIssuer issuer = MockIssuer.start();
    final InMemoryIdentityStore store = new InMemoryIdentityStore();
    boolean development = true;
    final LoginConnections connections = new LoginConnections(store,
            new OidcTestSignIn(MockIssuer.fetch(), Clock.systemUTC()), () -> development);

    InMemoryLoginConnectionsTest() {
        store.saveCustomer(TENANT, "Test Customer");
    }

    @AfterEach
    void stop() {
        issuer.close();
    }

    ConnectionValues values(URI iss, String audience) {
        return new ConnectionValues("test-idp", IdentityProviderType.OIDC, iss, "web-client", audience, null);
    }

    String goodToken() {
        return issuer.token(AUD, List.of("readers"), Duration.ofMinutes(5));
    }

    /** The first connection, made ACTIVE through draft, test and activate. */
    LoginConnection firstActive() {
        ConnectionOutcome d = connections.createDraft(TENANT, A, 0, values(issuer.issuer(), AUD), "it-admin");
        assertEquals(DRAFT_CREATED, d.result(), d.message());
        ConnectionOutcome v = connections.validate(d.connection().connectionId(), 1, TestSignIn.withToken(goodToken()),
                "it-admin");
        assertEquals(VALIDATED, v.result(), v.message());
        ConnectionOutcome a = connections.activate(v.connection().connectionId(), 2, "it-admin");
        assertEquals(ACTIVATED, a.result(), a.message());
        return a.connection();
    }

    @Test
    void draftTestActivateThenAChangeAndRollback() {
        LoginConnection first = firstActive();
        assertEquals(IdentityConnectionStatus.ACTIVE, first.status());
        assertEquals(3, first.configurationVersion());
        assertEquals(first, store.active(TENANT, A).orElseThrow());

        // A new draft copies the ACTIVE connection; people keep signing in through the ACTIVE one.
        ConnectionOutcome copy = connections.createDraft(TENANT, A, 3, null, "it-admin");
        assertEquals(DRAFT_CREATED, copy.result());
        assertTrue(ConnectionValues.same(first, copy.connection()));
        assertNotEquals(first.connectionId(), copy.connection().connectionId());
        assertEquals(first, store.active(TENANT, A).orElseThrow());

        UUID draft = copy.connection().connectionId();
        ConnectionOutcome edited = connections.updateDraft(draft, 1, values(issuer.issuer(), "api://changed"), "it-admin");
        assertEquals(DRAFT_UPDATED, edited.result());
        // The test fails for the token of the old audience, passes for the new one.
        ConnectionOutcome failed = connections.validate(draft, 2, TestSignIn.withToken(goodToken()), "it-admin");
        assertEquals(IDN_TEST_FAILED, failed.result());
        assertEquals(TestReport.Code.IDN_TEST_AUDIENCE, failed.test().failure());
        assertEquals(IdentityConnectionStatus.DRAFT, store.find(draft).orElseThrow().status());
        ConnectionOutcome ok = connections.validate(draft, 2,
                TestSignIn.withToken(issuer.token("api://changed", List.of("g"), Duration.ofMinutes(5))), "it-admin");
        assertEquals(VALIDATED, ok.result());

        // A later edit moves it back to DRAFT; a DRAFT cannot be activated.
        ConnectionOutcome again = connections.updateDraft(draft, 3, values(issuer.issuer(), "api://changed-2"), "it-admin");
        assertEquals(DRAFT_UPDATED, again.result());
        assertEquals(IdentityConnectionStatus.DRAFT, again.connection().status());
        assertEquals(IDN_NOT_VALIDATED, connections.activate(draft, 4, "it-admin").result());
        assertEquals(VALIDATED, connections.validate(draft, 4, TestSignIn.withToken(issuer.token("api://changed-2",
                List.of("g"), Duration.ofMinutes(5))), "it-admin").result());

        ConnectionOutcome on = connections.activate(draft, 5, "it-admin");
        assertEquals(ACTIVATED, on.result());
        assertEquals("api://changed-2", store.active(TENANT, A).orElseThrow().apiAudience());
        LoginConnection old = store.find(first.connectionId()).orElseThrow();
        assertEquals(IdentityConnectionStatus.DISABLED, old.status());
        assertEquals(4, old.configurationVersion());

        // Rollback: the previous ACTIVE values, as a new version of the connection that had them.
        ConnectionOutcome back = connections.rollback(TENANT, A, "it-admin");
        assertEquals(ROLLED_BACK, back.result());
        assertEquals(first.connectionId(), back.connection().connectionId());
        assertEquals(5, back.connection().configurationVersion());
        assertEquals(AUD, store.active(TENANT, A).orElseThrow().apiAudience());
        assertEquals(IdentityConnectionStatus.DISABLED, store.find(draft).orElseThrow().status());
        // A second rollback goes back to the other one.
        assertEquals("api://changed-2", connections.rollback(TENANT, A, "it-admin").connection().apiAudience());

        // Every step wrote a history row, source APP, with who.
        List<ConnectionChange> h = store.history(first.connectionId());
        assertEquals(List.of(IdentityConnectionStatus.DRAFT, IdentityConnectionStatus.VALIDATED,
                IdentityConnectionStatus.ACTIVE, IdentityConnectionStatus.DISABLED, IdentityConnectionStatus.ACTIVE,
                IdentityConnectionStatus.DISABLED), h.stream().map(ConnectionChange::status).toList());
        assertTrue(h.stream().allMatch(c -> c.source() == ChangeSource.APP && c.changedBy().equals("it-admin")));
    }

    /** P056 (LV-23): an ACTIVE connection is replaced only after a real sign-in with the draft passed. */
    @Test
    void replacingTheActiveConnectionNeedsATestSignInWithAToken() {
        LoginConnection active = firstActive();
        UUID draft = connections.createDraft(TENANT, A, 3, null, "a").connection().connectionId();
        assertEquals(VALIDATED, connections.validate(draft, 1, TestSignIn.issuerOnly(), "a").result());
        ConnectionOutcome refused = connections.activate(draft, 2, "a");
        assertEquals(IDN_TEST_SIGN_IN_REQUIRED, refused.result());
        assertTrue(refused.refused());
        assertEquals(active, store.active(TENANT, A).orElseThrow(), "people keep signing in");

        assertEquals(VALIDATED, connections.validate(draft, 2, TestSignIn.withToken(goodToken()), "a").result());
        // an edit takes the pass away, also when it is undone
        assertEquals(DRAFT_UPDATED, connections.updateDraft(draft, 3, values(issuer.issuer(), "api://x"), "a").result());
        assertEquals(DRAFT_UPDATED, connections.updateDraft(draft, 4, values(issuer.issuer(), AUD), "a").result());
        assertEquals(VALIDATED, connections.validate(draft, 5, TestSignIn.issuerOnly(), "a").result());
        assertEquals(IDN_TEST_SIGN_IN_REQUIRED, connections.activate(draft, 6, "a").result());
        assertEquals(VALIDATED, connections.validate(draft, 6, TestSignIn.withToken(goodToken()), "a").result());
        // a later failed test takes it away too
        assertEquals(IDN_TEST_FAILED, connections.validate(draft, 7, TestSignIn.withToken("no.real.token"), "a").result());
        assertEquals(IDN_NOT_VALIDATED, connections.activate(draft, 8, "a").result());
        assertEquals(VALIDATED, connections.validate(draft, 8, TestSignIn.withToken(goodToken()), "a").result());
        assertEquals(ACTIVATED, connections.activate(draft, 9, "a").result());
    }

    @Test
    void theVeryFirstConnectionNeedsNoToken() {
        ConnectionOutcome d = connections.createDraft(TENANT, A, 0, values(issuer.issuer(), AUD), "a");
        UUID id = d.connection().connectionId();
        assertEquals(VALIDATED, connections.validate(id, 1, TestSignIn.issuerOnly(), "a").result());
        assertEquals(ACTIVATED, connections.activate(id, 2, "a").result(), "nobody signs in yet, nobody is locked out");
    }

    /** P056 (LV-23): a draft call names its customer and product; another's draft is not found. */
    @Test
    void anotherCustomersDraftIsNotFound() {
        store.saveCustomer("TEN-TEST-0002", "Other Customer");
        UUID id = connections.createDraft(TENANT, A, 0, values(issuer.issuer(), AUD), "a").connection().connectionId();
        for (String[] other : new String[][]{{"TEN-TEST-0002", A}, {TENANT, "PRODUCT_B"}}) {
            assertEquals(IDN_CONNECTION_UNKNOWN, connections.updateDraft(other[0], other[1], id, 1,
                    values(HTTPS, AUD), "a").result());
            assertEquals(IDN_CONNECTION_UNKNOWN, connections.validate(other[0], other[1], id, 1,
                    TestSignIn.withToken(goodToken()), "a").result());
            assertEquals(IDN_CONNECTION_UNKNOWN, connections.activate(other[0], other[1], id, 1, "a").result());
            ConnectionOutcome x = connections.discardDraft(other[0], other[1], id, 1, "a");
            assertEquals(IDN_CONNECTION_UNKNOWN, x.result());
            assertNull(x.connection(), "nothing about it is told");
        }
        assertEquals(1, store.find(id).orElseThrow().configurationVersion(), "unchanged");
        assertEquals(VALIDATED, connections.validate(TENANT, A, id, 1, TestSignIn.withToken(goodToken()), "a").result());
        assertEquals(ACTIVATED, connections.activate(TENANT, A, id, 2, "a").result());
        UUID next = connections.createDraft(TENANT, A, 3, null, "a").connection().connectionId();
        assertEquals(DRAFT_UPDATED, connections.updateDraft(TENANT, A, next, 1, values(issuer.issuer(), "api://n"), "a").result());
        assertEquals(DRAFT_DISCARDED, connections.discardDraft(TENANT, A, next, 2, "a").result());
    }

    @Test
    void atMostOneDraftAndVersionsAreChecked() {
        LoginConnection active = firstActive();
        assertEquals(IDN_VERSION_CONFLICT, connections.createDraft(TENANT, A, 1, null, "a").result());
        ConnectionOutcome d = connections.createDraft(TENANT, A, active.configurationVersion(), null, "a");
        assertEquals(DRAFT_CREATED, d.result());
        assertEquals(IDN_DRAFT_EXISTS, connections.createDraft(TENANT, A, active.configurationVersion(), null, "b").result());
        UUID id = d.connection().connectionId();
        assertEquals(IDN_VERSION_CONFLICT, connections.updateDraft(id, 7, values(HTTPS, AUD), "a").result());
        assertEquals(IDN_VERSION_CONFLICT, connections.validate(id, 7, TestSignIn.issuerOnly(), "a").result());
        assertEquals(UNCHANGED, connections.updateDraft(id, 1, ConnectionValues.of(active), "a").result());
        assertEquals(IDN_NOT_A_DRAFT, connections.updateDraft(active.connectionId(), active.configurationVersion(),
                values(HTTPS, AUD), "a").result());
        assertEquals(IDN_NOT_A_DRAFT, connections.validate(active.connectionId(), active.configurationVersion(),
                TestSignIn.issuerOnly(), "a").result());
        assertEquals(IDN_NOT_A_DRAFT, connections.activate(active.connectionId(), active.configurationVersion(), "a")
                .result());
        assertEquals(IDN_CONNECTION_UNKNOWN, connections.validate(UUID.randomUUID(), 1, TestSignIn.issuerOnly(), "a")
                .result());

        // Discarding the draft frees the place for a new one.
        assertEquals(DRAFT_DISCARDED, connections.discardDraft(id, 1, "a").result());
        assertEquals(IdentityConnectionStatus.DISABLED, store.find(id).orElseThrow().status());
        assertEquals(DRAFT_CREATED, connections.createDraft(TENANT, A, active.configurationVersion(), null, "a").result());
        // A discarded draft was never ACTIVE: no rollback target.
        assertEquals(IDN_NO_PREVIOUS_CONNECTION, connections.rollback(TENANT, A, "a").result());
    }

    @Test
    void theFirstDraftNeedsValuesAndAStoredCustomer() {
        assertEquals(IDN_VALUES_REQUIRED, connections.createDraft(TENANT, A, 0, null, "a").result());
        assertEquals(IDN_VERSION_CONFLICT, connections.createDraft(TENANT, A, 2, values(HTTPS, AUD), "a").result());
        assertEquals(IDN_CUSTOMER_UNKNOWN, connections.createDraft("TEN-TEST-0099", A, 0, values(HTTPS, AUD), "a")
                .result());
        ConnectionOutcome bad = connections.createDraft(TENANT, A, 0, values(HTTPS, " "), "a");
        assertEquals(IDN_VALUES_INVALID, bad.result());
        assertTrue(bad.message().contains("apiAudience"), bad.message());
        ConnectionOutcome secret = connections.createDraft(TENANT, A, 0,
                new ConnectionValues("idp", IdentityProviderType.OIDC, HTTPS, null, AUD, "plain-secret-value"), "a");
        assertEquals(IDN_VALUES_INVALID, secret.result());
        assertFalse(secret.message().contains("plain-secret-value"));
        assertEquals(IDN_VALUES_INVALID, connections.createDraft(TENANT, A, 0,
                values(URI.create("https://login.example.test/?tenant=1"), AUD), "a").result());
        assertEquals(IDN_NO_PREVIOUS_CONNECTION, connections.rollback(TENANT, A, "a").result());
    }

    @Test
    void anHttpIssuerNeedsALoopbackHostAndADevelopmentLicence() {
        development = false;
        assertEquals(IDN_HTTP_ISSUER_NOT_ALLOWED, connections.createDraft(TENANT, A, 0, values(issuer.issuer(), AUD), "a")
                .result());
        development = true;
        assertEquals(IDN_HTTP_ISSUER_NOT_ALLOWED, connections.createDraft(TENANT, A, 0,
                values(URI.create("http://login.example.test/"), AUD), "a").result());

        // Made under a development licence; the licence is then replaced: test and activate are refused.
        ConnectionOutcome d = connections.createDraft(TENANT, A, 0, values(issuer.issuer(), AUD), "a");
        assertEquals(DRAFT_CREATED, d.result());
        assertEquals(VALIDATED, connections.validate(d.connection().connectionId(), 1, TestSignIn.issuerOnly(), "a")
                .result());
        development = false;
        assertEquals(IDN_HTTP_ISSUER_NOT_ALLOWED, connections.activate(d.connection().connectionId(), 2, "a").result());
        assertEquals(IDN_HTTP_ISSUER_NOT_ALLOWED, connections.validate(d.connection().connectionId(), 2,
                TestSignIn.issuerOnly(), "a").result());
        assertTrue(store.active(TENANT, A).isEmpty());
    }

    @Test
    void eachTestFailureHasItsCodeAndAFailureOnAValidatedDraftMakesItADraftAgain() {
        ConnectionOutcome d = connections.createDraft(TENANT, A, 0, values(issuer.issuer(), AUD), "a");
        UUID id = d.connection().connectionId();
        assertEquals(VALIDATED, connections.validate(id, 1, TestSignIn.issuerOnly(), "a").result());

        issuer.discoveryIssuer("http://127.0.0.1:1/other");
        ConnectionOutcome r = connections.validate(id, 2, TestSignIn.issuerOnly(), "a");
        assertEquals(IDN_TEST_FAILED, r.result());
        assertEquals(TestReport.Code.IDN_TEST_DISCOVERY_MISMATCH, r.test().failure());
        assertEquals(IdentityConnectionStatus.DRAFT, r.connection().status());
        assertEquals(3, r.connection().configurationVersion());
        issuer.discoveryIssuer(issuer.issuer().toString());

        long v = 3;
        issuer.noKeys();
        assertEquals(TestReport.Code.IDN_TEST_KEYS_MISSING, connections.validate(id, v, TestSignIn.issuerOnly(), "a")
                .test().failure());
        MockIssuer fresh = MockIssuer.start();
        try {
            ConnectionOutcome moved = connections.updateDraft(id, v, values(fresh.issuer(), AUD), "a");
            v = moved.connection().configurationVersion();
            var claims = fresh.claims(AUD, Duration.ofMinutes(5));
            assertEquals(TestReport.Code.IDN_TEST_TOKEN_SIGNATURE, connections.validate(id, v,
                    TestSignIn.withToken(fresh.signWithOtherKey(claims)), "a").test().failure());
            assertEquals(TestReport.Code.IDN_TEST_GROUPS_CLAIM_MISSING, connections.validate(id, v,
                    TestSignIn.withToken(fresh.sign(claims)), "a").test().failure());
            assertEquals(TestReport.Code.IDN_TEST_EXPIRED, connections.validate(id, v,
                    TestSignIn.withToken(fresh.token(AUD, List.of("g"), Duration.ofMinutes(-10))), "a").test().failure());
            fresh.down();
            assertEquals(TestReport.Code.IDN_TEST_ISSUER_UNREACHABLE, connections.validate(id, v,
                    TestSignIn.issuerOnly(), "a").test().failure());
        } finally {
            fresh.close();
        }
        assertEquals(IdentityConnectionStatus.DRAFT, store.find(id).orElseThrow().status(),
                "failed tests leave a DRAFT as it is");
    }

    @Test
    void noDevelopmentLicenceMeansNoHttpIssuer() {
        LoginConnections strict = new LoginConnections(store, new OidcTestSignIn(MockIssuer.fetch(), Clock.systemUTC()),
                DevelopmentLicence.NONE);
        assertEquals(IDN_HTTP_ISSUER_NOT_ALLOWED, strict.createDraft(TENANT, A, 0, values(issuer.issuer(), AUD), "a")
                .result());
        assertEquals(DRAFT_CREATED, strict.createDraft(TENANT, A, 0, values(HTTPS, AUD), "a").result());
    }
}
