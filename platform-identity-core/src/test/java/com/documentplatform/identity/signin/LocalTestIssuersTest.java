package com.documentplatform.identity.signin;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Optional;

import static com.documentplatform.identity.signin.LocalTestIssuers.Problem.HTTP_NOT_ALLOWED;
import static com.documentplatform.identity.signin.LocalTestIssuers.Problem.NOT_AN_ISSUER;
import static org.junit.jupiter.api.Assertions.*;

/** P031 B2: http issuers only on this machine, and only under a development licence. */
class LocalTestIssuersTest {

    @Test
    void httpsIssuersAreAllowedAsBefore() {
        assertEquals(Optional.empty(), LocalTestIssuers.check(URI.create("https://login.example.test/oauth2/default"), false));
        assertEquals(Optional.empty(), LocalTestIssuers.check(URI.create("https://login.example.test/"), true));
    }

    @Test
    void httpOnLoopbackIsAllowedOnlyWithADevelopmentLicence() {
        for (String uri : new String[]{"http://localhost:8080/realms/test", "http://127.0.0.1:9000/",
                "http://[::1]:8080/issuer", "http://LOCALHOST/x"}) {
            assertEquals(Optional.empty(), LocalTestIssuers.check(URI.create(uri), true), uri);
            assertEquals(Optional.of(HTTP_NOT_ALLOWED), LocalTestIssuers.check(URI.create(uri), false), uri);
            assertTrue(LocalTestIssuers.isLocalHttp(URI.create(uri)));
        }
    }

    @Test
    void httpElsewhereIsNeverAllowed() {
        for (String uri : new String[]{"http://login.example.test/", "http://10.0.0.5/", "http://127.0.0.2/",
                "http://localhost.example.test/", "http://0.0.0.0/"}) {
            assertEquals(Optional.of(HTTP_NOT_ALLOWED), LocalTestIssuers.check(URI.create(uri), true), uri);
            assertFalse(LocalTestIssuers.isLocalHttp(URI.create(uri)));
        }
    }

    @Test
    void everyOtherRuleIsUnchanged() {
        for (String uri : new String[]{"https://login.example.test/?q=1", "https://login.example.test/#f",
                "https://user@login.example.test/", "ftp://login.example.test/", "http://localhost/?q=1",
                "/relative"}) {
            assertEquals(Optional.of(NOT_AN_ISSUER), LocalTestIssuers.check(URI.create(uri), true), uri);
        }
        assertEquals(Optional.of(NOT_AN_ISSUER), LocalTestIssuers.check(null, true));
    }
}
