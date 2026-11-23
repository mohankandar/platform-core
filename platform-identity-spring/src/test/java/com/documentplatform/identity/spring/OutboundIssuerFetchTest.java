package com.documentplatform.identity.spring;

import com.documentplatform.identity.signin.IssuerFetch;
import com.documentplatform.identity.signin.OidcTestSignIn;
import com.documentplatform.identity.signin.TestReport;
import com.documentplatform.identity.signin.TestSignIn;
import com.documentplatform.identity.testkit.MockIssuer;
import com.documentplatform.outbound.Installation;
import com.documentplatform.outbound.Outbound;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** P031 B1/B2: the test sign-in reads the issuer through the outbound rules (platform-outbound). */
class OutboundIssuerFetchTest {

    final MockIssuer issuer = MockIssuer.start();

    @AfterEach
    void stop() {
        issuer.close();
    }

    @Test
    void aLocalTestIssuerIsReadOnThisMachineAndTheWholeTestPasses() {
        OutboundIssuerFetch fetch = new OutboundIssuerFetch(new Outbound(Installation.hostedService()));
        TestReport r = new OidcTestSignIn(fetch, Clock.systemUTC()).run(issuer.issuer(), "api://product-a",
                TestSignIn.withToken(issuer.token("api://product-a", List.of("g"), Duration.ofMinutes(5))), true);
        assertTrue(r.passed(), r.message());
    }

    @Test
    void withoutTheLocalTestRuleHttpAndLoopbackAreRefusedByTheOutboundRules() {
        OutboundIssuerFetch strict = new OutboundIssuerFetch(new Outbound(Installation.onPremises()));
        IssuerFetch.Refused e = assertThrows(IssuerFetch.Refused.class,
                () -> strict.get(issuer.issuer(), 1000, false));
        assertEquals("OUT_ADDRESS_NOT_SECURE", e.reason());
        // http elsewhere is never called directly, even when marked local
        e = assertThrows(IssuerFetch.Refused.class, () -> strict.get(URI.create("http://login.example.test/"), 1000, true));
        assertEquals("OUT_ADDRESS_NOT_SECURE", e.reason());
        // https to loopback or cloud metadata: the address guard
        e = assertThrows(IssuerFetch.Refused.class, () -> strict.get(URI.create("https://127.0.0.1:1/"), 1000, false));
        assertEquals("OUT_ADDRESS_BLOCKED", e.reason());
        e = assertThrows(IssuerFetch.Refused.class, () -> strict.get(URI.create("https://169.254.169.254/"), 1000, true));
        assertEquals("OUT_ADDRESS_BLOCKED", e.reason());

        TestReport r = new OidcTestSignIn(strict, Clock.systemUTC()).run(issuer.issuer(), "api://product-a",
                TestSignIn.issuerOnly(), false);
        assertEquals(TestReport.Code.IDN_TEST_ISSUER_UNREACHABLE, r.failure());
        assertEquals("OUT_ADDRESS_NOT_SECURE", r.reason());
        assertEquals(0, issuer.calls(), "nothing reached the issuer");
    }

    @Test
    void aBodyOverTheLimitIsRefused() {
        OutboundIssuerFetch fetch = new OutboundIssuerFetch(new Outbound(Installation.onPremises()));
        IssuerFetch.Refused e = assertThrows(IssuerFetch.Refused.class,
                () -> fetch.get(URI.create(issuer.issuer() + "/.well-known/openid-configuration"), 10, true));
        assertEquals("TOO_LARGE", e.reason());
    }
}
