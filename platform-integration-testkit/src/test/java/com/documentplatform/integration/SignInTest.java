package com.documentplatform.integration;

import com.documentplatform.integration.config.IntegrationConfig;
import com.documentplatform.integration.config.SignIn;
import com.documentplatform.integration.config.SignIn.OAuth2ClientCredentials;
import com.documentplatform.integration.result.AttemptResult;
import com.documentplatform.integration.result.Outcome;
import com.documentplatform.integration.safety.Installation;
import com.documentplatform.integration.testkit.MockDestination;
import com.documentplatform.integration.testkit.TestCertificates;
import com.documentplatform.notifications.secret.SecretRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/** Every sign-in type against the mock destination and its token endpoint. */
class SignInTest {

    private Fx fx = new Fx();

    @AfterEach
    void close() {
        fx.close();
    }

    private AttemptResult send(IntegrationConfig c) {
        return fx.engine.attempt(Fx.stored(c), Fx.delivery(2));
    }

    @Test
    void noneWorksOnlyWhereTheInstallationAllowsIt() {
        IntegrationConfig c = Fx.oneStep(fx.mock.address(), new SignIn.None());
        // On premises but not switched on: refused before anything is sent.
        AttemptResult off = send(c);
        assertEquals(Outcome.NOT_CONFIGURED, off.outcome());
        assertEquals(IntegrationCode.INT_CONFIG_INVALID, off.code()); // the configuration check fails first
        assertTrue(fx.mock.received().isEmpty());

        fx.close();
        fx = new Fx(MockDestination.start(), Installation.onPremises().withInternalAddresses(true).withSignInNone(true));
        c = Fx.oneStep(fx.mock.address(), new SignIn.None());
        AttemptResult on = send(c);
        assertEquals(Outcome.SENT, on.outcome());
        assertEquals("NONE", fx.mock.calls().get(0).signIn());
        assertTrue(fx.mock.calls().get(0).header("authorization").isEmpty());
    }

    @Test
    void basic() {
        fx.secrets.put(SecretRef.of("int/basic"), "pa55-word");
        fx.mock.expectBasic("sender", "pa55-word");
        AttemptResult r = send(Fx.twoStep(fx.mock.address(), new SignIn.Basic("sender", SecretRef.of("int/basic"))));
        assertEquals(Outcome.SENT, r.outcome(), r.toString());
        assertEquals(3, fx.mock.calls().size());
        assertTrue(fx.mock.calls().stream().allMatch(x -> "BASIC".equals(x.signIn())));

        fx.secrets.put(SecretRef.of("int/basic"), "wrong");
        AttemptResult refused = send(Fx.twoStep(fx.mock.address(), new SignIn.Basic("sender",
                SecretRef.of("int/basic"))));
        assertEquals(Outcome.SIGN_IN_FAILED, refused.outcome());
        assertEquals(IntegrationCode.INT_SIGN_IN_REFUSED, refused.code());
        assertEquals("MOCK-AUTH-REQUIRED", refused.destinationCode());
        assertEquals(401, refused.httpStatus());
    }

    @Test
    void apiKeyGoesInTheHeaderNeverTheAddress() {
        fx.secrets.put(SecretRef.of("int/key"), "k-123456789");
        fx.mock.expectApiKey("X-Api-Key", "k-123456789");
        AttemptResult r = send(Fx.twoStep(fx.mock.address(), new SignIn.ApiKey("X-Api-Key", SecretRef.of("int/key"))));
        assertEquals(Outcome.SENT, r.outcome());
        for (MockDestination.Received x : fx.mock.calls()) {
            assertEquals("API_KEY", x.signIn());
            assertNull(x.query());
            assertFalse(x.path().contains("k-123456789"));
            assertEquals("[present]", x.header("x-api-key").orElseThrow());
        }
    }

    @Test
    void fixedBearer() {
        fx.secrets.put(SecretRef.of("int/bearer"), "fixed-token-abc");
        fx.mock.expectBearer("fixed-token-abc");
        AttemptResult r = send(Fx.twoStep(fx.mock.address(), new SignIn.BearerFixed(SecretRef.of("int/bearer"))));
        assertEquals(Outcome.SENT, r.outcome());
        assertTrue(fx.mock.calls().stream().allMatch(x -> "BEARER".equals(x.signIn())));
    }

    @Test
    void oauth2ClientSecretInTheFormIsFetchedOnceAndCached() {
        fx.secrets.put(SecretRef.of("int/client"), "client-secret-xyz");
        fx.mock.expectOAuth2().registerClient("sender-app", "client-secret-xyz").requireScope("ingest");
        IntegrationConfig c = Fx.twoStep(fx.mock.address(), OAuth2ClientCredentials.secret(fx.mock.tokenAddress(),
                "sender-app", SecretRef.of("int/client"), "ingest"));
        assertEquals(Outcome.SENT, send(c).outcome());
        assertEquals(Outcome.SENT, send(c).outcome());
        assertEquals(1, fx.mock.tokensIssued(), "the token is kept until shortly before it expires");
        MockDestination.TokenRequest t = fx.mock.tokenRequests().get(0);
        assertEquals("client_secret_post", t.clientAuth());
        assertEquals("client_credentials", t.grantType());
        assertEquals("ingest", t.scope());
        assertTrue(fx.mock.calls().stream().allMatch(x -> "OAUTH2".equals(x.signIn())));
    }

    @Test
    void aHeldTokenIsDroppedWhenTheClientCertificateInUseChanges() {
        // P027 B0.2: the token is bound to the certificate in use when it was fetched (a switch by the admin, at its
        // set time, or on another instance); with another certificate it is never used again.
        fx.secrets.put(SecretRef.of("int/client"), "client-secret-xyz");
        fx.mock.expectOAuth2().registerClient("sender-app", "client-secret-xyz");
        OAuth2ClientCredentials client = OAuth2ClientCredentials.secret(fx.mock.tokenAddress(), "sender-app",
                SecretRef.of("int/client"), null);
        com.documentplatform.integration.signin.ClientCredentialsTokens tokens =
                new com.documentplatform.integration.signin.ClientCredentialsTokens(
                        new com.documentplatform.integration.http.Outbound(fx.installation), java.time.Clock.systemUTC());
        com.documentplatform.integration.config.Limits limits =
                com.documentplatform.integration.config.Limits.DEFAULT;
        assertTrue(tokens.token(client, fx.secrets, limits, true, null, "aa11").ok());
        assertTrue(tokens.token(client, fx.secrets, limits, true, null, "aa11").ok());
        assertEquals(1, fx.mock.tokensIssued(), "held while the same certificate is in use");
        assertTrue(tokens.token(client, fx.secrets, limits, true, null, "bb22").ok());
        assertEquals(2, fx.mock.tokensIssued(), "the switch dropped the held token at once");
        assertTrue(tokens.token(client, fx.secrets, limits, true, null, "bb22").ok());
        assertEquals(2, fx.mock.tokensIssued());
        assertTrue(tokens.token(client, fx.secrets, limits, true, null, null).ok());
        assertEquals(3, fx.mock.tokensIssued(), "no certificate any more is a change too");
    }

    @Test
    void oauth2ClientSecretAsBasicWithAudience() {
        fx.secrets.put(SecretRef.of("int/client"), "s3cret:with&odd=chars");
        fx.mock.expectOAuth2().registerClient("sender-app", "s3cret:with&odd=chars").requireAudience("api://dest");
        IntegrationConfig c = Fx.twoStep(fx.mock.address(), OAuth2ClientCredentials.secret(fx.mock.tokenAddress(),
                        "sender-app", SecretRef.of("int/client"), null).withAudience("api://dest")
                .withClientAuth(OAuth2ClientCredentials.ClientAuth.BASIC));
        assertEquals(Outcome.SENT, send(c).outcome());
        MockDestination.TokenRequest t = fx.mock.tokenRequests().get(0);
        assertEquals("client_secret_basic", t.clientAuth());
        assertEquals("api://dest", t.audience());
    }

    @Test
    void oauth2CertificateClientAssertion(@TempDir Path dir) throws Exception {
        TestCertificates.Credential cred = TestCertificates.credential(dir, "sender-cert", -1, 30);
        fx.secrets.put(SecretRef.of("int/cert"), cred.pem());
        fx.mock.expectOAuth2().registerCertificateClient("sender-app", cred.certificate());
        IntegrationConfig c = Fx.twoStep(fx.mock.address(), OAuth2ClientCredentials.certificate(
                fx.mock.tokenAddress(), "sender-app", SecretRef.of("int/cert"), "ingest"));
        AttemptResult r = send(c);
        assertEquals(Outcome.SENT, r.outcome(), r.toString());
        assertEquals("private_key_jwt", fx.mock.tokenRequests().get(0).clientAuth());
    }

    @Test
    void oauth2CertificateExpiredOrUnusable(@TempDir Path dir) throws Exception {
        TestCertificates.Credential old = TestCertificates.credential(dir, "old-cert", -40, 10);
        fx.secrets.put(SecretRef.of("int/cert"), old.pem());
        fx.secrets.put(SecretRef.of("int/bad"), "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n");
        fx.mock.expectOAuth2();
        AttemptResult expired = send(Fx.twoStep(fx.mock.address(), OAuth2ClientCredentials.certificate(
                fx.mock.tokenAddress(), "sender-app", SecretRef.of("int/cert"), null)));
        assertEquals(Outcome.SIGN_IN_FAILED, expired.outcome());
        assertEquals(IntegrationCode.INT_TOKEN_CREDENTIAL_EXPIRED, expired.code());
        AttemptResult bad = send(Fx.twoStep(fx.mock.address(), OAuth2ClientCredentials.certificate(
                fx.mock.tokenAddress(), "sender-app", SecretRef.of("int/bad"), null)));
        assertEquals(IntegrationCode.INT_TOKEN_CREDENTIAL_INVALID, bad.code());
        assertTrue(fx.mock.tokenRequests().isEmpty(), "nothing is sent with an unusable credential");
    }

    @Test
    void tokenEndpointFailuresHaveSpecificCodes() {
        fx.secrets.put(SecretRef.of("int/client"), "client-secret-xyz");
        fx.mock.expectOAuth2().registerClient("sender-app", "client-secret-xyz");
        IntegrationConfig c = Fx.twoStep(fx.mock.address(), OAuth2ClientCredentials.secret(fx.mock.tokenAddress(),
                "sender-app", SecretRef.of("int/client"), null));

        fx.mock.tokenMode(MockDestination.TokenMode.REFUSE_CLIENT);
        AttemptResult refused = send(c);
        assertEquals(Outcome.SIGN_IN_FAILED, refused.outcome());
        assertEquals(IntegrationCode.INT_TOKEN_CREDENTIAL_REFUSED, refused.code());

        fx.mock.tokenMode(MockDestination.TokenMode.REFUSE_REQUEST);
        assertEquals(IntegrationCode.INT_TOKEN_REFUSED, send(c).code());

        fx.mock.tokenMode(MockDestination.TokenMode.BAD_REPLY);
        assertEquals(IntegrationCode.INT_TOKEN_UNEXPECTED_REPLY, send(c).code());

        fx.mock.tokenMode(MockDestination.TokenMode.DOWN);
        AttemptResult down = send(c);
        assertEquals(Outcome.CANT_CONNECT, down.outcome());
        assertEquals(IntegrationCode.INT_TOKEN_UNREACHABLE, down.code());
        assertTrue(fx.mock.calls().isEmpty(), "no call without a token");
    }

    @Test
    void aTokenTheDestinationRefusesIsDroppedSoTheNextAttemptGetsANewOne() {
        fx.secrets.put(SecretRef.of("int/client"), "client-secret-xyz");
        fx.mock.expectOAuth2().registerClient("sender-app", "client-secret-xyz");
        IntegrationConfig c = Fx.twoStep(fx.mock.address(), OAuth2ClientCredentials.secret(fx.mock.tokenAddress(),
                "sender-app", SecretRef.of("int/client"), null));
        assertEquals(Outcome.SENT, send(c).outcome());
        fx.mock.revokeTokens();
        AttemptResult refused = send(c);
        assertEquals(Outcome.SIGN_IN_FAILED, refused.outcome());
        assertEquals(IntegrationCode.INT_SIGN_IN_REFUSED, refused.code());
        assertEquals(Outcome.SENT, send(c).outcome());
        assertEquals(2, fx.mock.tokensIssued());
    }

    @Test
    void shortLivedTokensAreRenewed() {
        fx.secrets.put(SecretRef.of("int/client"), "client-secret-xyz");
        fx.mock.expectOAuth2().registerClient("sender-app", "client-secret-xyz").tokenLifetime(Duration.ofSeconds(2));
        IntegrationConfig c = Fx.oneStep(fx.mock.address(), OAuth2ClientCredentials.secret(fx.mock.tokenAddress(),
                "sender-app", SecretRef.of("int/client"), null));
        assertEquals(Outcome.SENT, send(c).outcome());
        // A 2-second token is kept for half its life at most.
        try {
            Thread.sleep(1200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertEquals(Outcome.SENT, send(c).outcome());
        assertEquals(2, fx.mock.tokensIssued());
    }

    @Test
    void missingOrUnreachableSecrets() {
        fx.mock.expectBearer("x");
        IntegrationConfig c = Fx.oneStep(fx.mock.address(), new SignIn.BearerFixed(SecretRef.of("int/none")));
        AttemptResult missing = send(c);
        assertEquals(Outcome.NOT_CONFIGURED, missing.outcome());
        assertEquals(IntegrationCode.INT_SECRET_NOT_FOUND, missing.code());
        fx.secrets.unavailable(true);
        AttemptResult down = send(c);
        assertEquals(Outcome.CANT_CONNECT, down.outcome());
        assertEquals(IntegrationCode.INT_SECRET_UNAVAILABLE, down.code());
        assertTrue(fx.mock.received().isEmpty());
    }

    @Test
    void microsoftPresetBuildsTheEntraTokenAddress() {
        OAuth2ClientCredentials m = OAuth2ClientCredentials.microsoft("contoso.onmicrosoft.com",
                "11111111-2222-3333-4444-555555555555", OAuth2ClientCredentials.CredentialType.CERTIFICATE,
                SecretRef.of("int/ms"), "api://dest/.default");
        assertEquals("https://login.microsoftonline.com/contoso.onmicrosoft.com/oauth2/v2.0/token",
                m.tokenAddress().toString());
        assertEquals("api://dest/.default", m.scope());
    }
}
