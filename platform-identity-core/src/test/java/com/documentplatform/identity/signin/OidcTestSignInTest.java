package com.documentplatform.identity.signin;

import com.documentplatform.identity.signin.TestReport.Code;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The test sign-in's checks, one failure code at a time, against an in-memory issuer (an https one). */
class OidcTestSignInTest {

    static final URI ISSUER = URI.create("https://login.example.test/oauth2/default");
    static final String AUD = "api://product-a";
    static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    final KeyPair rsa = generate("RSA", null);
    final KeyPair other = generate("RSA", null);
    final KeyPair ec = generate("EC", "secp256r1");

    /** address → (status, body); a missing address is refused like a connection failure. */
    final Map<String, Object[]> pages = new HashMap<>();
    final List<String> fetched = new ArrayList<>();

    final IssuerFetch fetch = (uri, max, local) -> {
        fetched.add(uri + (local ? " local" : ""));
        Object[] page = pages.get(uri.toString());
        if (page == null) throw new IssuerFetch.Refused("INT_CANT_CONNECT");
        return new IssuerFetch.Response((Integer) page[0], ((String) page[1]).getBytes(StandardCharsets.UTF_8));
    };

    OidcTestSignInTest() {
        discovery("{\"issuer\":\"" + ISSUER + "\",\"jwks_uri\":\"" + ISSUER + "/v1/keys\"}");
        keys(jwks(rsaJwk(rsa, "k1"), ecJwk(ec, "e1")));
    }

    void discovery(String body) {
        pages.put(ISSUER + "/.well-known/openid-configuration", new Object[]{200, body});
    }

    void keys(String body) {
        pages.put(ISSUER + "/v1/keys", new Object[]{200, body});
    }

    TestReport run(TestSignIn in) {
        return new OidcTestSignIn(fetch, CLOCK).run(ISSUER, AUD, in, false);
    }

    Map<String, Object> claims() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("iss", ISSUER.toString());
        c.put("aud", List.of("other", AUD));
        c.put("exp", NOW.plusSeconds(300).getEpochSecond());
        c.put("groups", List.of("readers"));
        return c;
    }

    @Test
    void withoutATokenTheDiscoveryDocumentAndKeysAreChecked() {
        TestReport r = run(TestSignIn.issuerOnly());
        assertTrue(r.passed(), r.message());
        assertFalse(r.tokenChecked());
        assertEquals(List.of(ISSUER + "/.well-known/openid-configuration", ISSUER + "/v1/keys"), fetched);
    }

    @Test
    void aGoodTokenPassesWithRsaAndEcKeys() {
        TestReport r = run(TestSignIn.withToken(token(claims(), rsa, "RS256", "k1")));
        assertTrue(r.passed(), r.message());
        assertTrue(r.tokenChecked());
        assertTrue(run(TestSignIn.withToken(token(claims(), ec, "ES256", "e1"))).passed());
        // RSASSA-PSS with the same RSA key
        assertTrue(run(TestSignIn.withToken(token(claims(), rsa, "PS256", "k1"))).passed());
        // an aud that is a single string
        Map<String, Object> c = claims();
        c.put("aud", AUD);
        assertTrue(run(TestSignIn.withToken(token(c, rsa, "RS256", "k1"))).passed());
    }

    @Test
    void anUnreachableIssuerOrAnHttpErrorIsIssuerUnreachable() {
        pages.clear();
        TestReport r = run(TestSignIn.issuerOnly());
        assertEquals(Code.IDN_TEST_ISSUER_UNREACHABLE, r.failure());
        assertEquals("INT_CANT_CONNECT", r.reason());
        pages.put(ISSUER + "/.well-known/openid-configuration", new Object[]{503, "{}"});
        r = run(TestSignIn.issuerOnly());
        assertEquals(Code.IDN_TEST_ISSUER_UNREACHABLE, r.failure());
        assertEquals("HTTP_503", r.reason());
    }

    @Test
    void anotherIssuerOrAnUnreadableDocumentIsDiscoveryMismatch() {
        discovery("{\"issuer\":\"" + ISSUER + "/\",\"jwks_uri\":\"" + ISSUER + "/v1/keys\"}");
        assertEquals(Code.IDN_TEST_DISCOVERY_MISMATCH, run(TestSignIn.issuerOnly()).failure());
        discovery("<html>not json</html>");
        assertEquals(Code.IDN_TEST_DISCOVERY_MISMATCH, run(TestSignIn.issuerOnly()).failure());
        discovery("{\"issuer\":\"a\",\"issuer\":\"b\"}");
        assertEquals(Code.IDN_TEST_DISCOVERY_MISMATCH, run(TestSignIn.issuerOnly()).failure());
    }

    @Test
    void noJwksUriAnEmptyOrUnreachableKeySetOrAnHttpKeyAddressIsKeysMissing() {
        discovery("{\"issuer\":\"" + ISSUER + "\"}");
        assertEquals(Code.IDN_TEST_KEYS_MISSING, run(TestSignIn.issuerOnly()).failure());

        discovery("{\"issuer\":\"" + ISSUER + "\",\"jwks_uri\":\"http://login.example.test/keys\"}");
        assertEquals(Code.IDN_TEST_KEYS_MISSING, run(TestSignIn.issuerOnly()).failure());
        assertFalse(fetched.contains("http://login.example.test/keys"), "an http key address is never called");

        discovery("{\"issuer\":\"" + ISSUER + "\",\"jwks_uri\":\"" + ISSUER + "/v1/keys\"}");
        keys("{\"keys\":[]}");
        assertEquals(Code.IDN_TEST_KEYS_MISSING, run(TestSignIn.issuerOnly()).failure());
        keys("{\"keys\":[{\"kty\":\"oct\",\"k\":\"c2VjcmV0\"}]}");
        assertEquals(Code.IDN_TEST_KEYS_MISSING, run(TestSignIn.issuerOnly()).failure());
        keys("{\"keys\":[" + rsaJwk(rsa, "enc").replace("\"use\":\"sig\"", "\"use\":\"enc\"") + "]}");
        assertEquals(Code.IDN_TEST_KEYS_MISSING, run(TestSignIn.issuerOnly()).failure());
        pages.remove(ISSUER + "/v1/keys");
        TestReport r = run(TestSignIn.issuerOnly());
        assertEquals(Code.IDN_TEST_KEYS_MISSING, r.failure());
        assertEquals("INT_CANT_CONNECT", r.reason());
    }

    @Test
    void textThatIsNotASignedJwtIsTokenUnreadable() {
        for (String t : List.of("not-a-token", "a.b", "a.b.c.d", "!!!.###.$$$", "e30.e30.")) {
            assertEquals(Code.IDN_TEST_TOKEN_UNREADABLE, run(TestSignIn.withToken(t)).failure(), t);
        }
    }

    @Test
    void aWrongKeyAnUnknownKeyIdOrAnUnacceptedAlgorithmIsTokenSignature() {
        assertEquals(Code.IDN_TEST_TOKEN_SIGNATURE,
                run(TestSignIn.withToken(token(claims(), other, "RS256", "k1"))).failure());
        assertEquals(Code.IDN_TEST_TOKEN_SIGNATURE,
                run(TestSignIn.withToken(token(claims(), rsa, "RS256", "unknown"))).failure());
        String none = b64("{\"alg\":\"none\"}") + "." + b64(json(claims())) + ".AAAA";
        assertEquals(Code.IDN_TEST_TOKEN_SIGNATURE, run(TestSignIn.withToken(none)).failure());
        String hs = b64("{\"alg\":\"HS256\",\"kid\":\"k1\"}") + "." + b64(json(claims())) + ".AAAA";
        assertEquals(Code.IDN_TEST_TOKEN_SIGNATURE, run(TestSignIn.withToken(hs)).failure());
        // a token with a changed claim no longer verifies
        String good = token(claims(), rsa, "RS256", "k1");
        String[] p = good.split("\\.");
        Map<String, Object> c = claims();
        c.put("groups", List.of("admins"));
        String tampered = p[0] + "." + b64(json(c)) + "." + p[2];
        assertEquals(Code.IDN_TEST_TOKEN_SIGNATURE, run(TestSignIn.withToken(tampered)).failure());
    }

    @Test
    void issuerAudienceExpiryAndGroupsEachHaveTheirCode() {
        Map<String, Object> c = claims();
        c.put("iss", "https://other.example.test/");
        assertEquals(Code.IDN_TEST_TOKEN_ISSUER, run(TestSignIn.withToken(token(c, rsa, "RS256", "k1"))).failure());

        c = claims();
        c.put("aud", List.of("api://another"));
        assertEquals(Code.IDN_TEST_AUDIENCE, run(TestSignIn.withToken(token(c, rsa, "RS256", "k1"))).failure());
        c.remove("aud");
        assertEquals(Code.IDN_TEST_AUDIENCE, run(TestSignIn.withToken(token(c, rsa, "RS256", "k1"))).failure());

        c = claims();
        c.put("exp", NOW.minusSeconds(120).getEpochSecond());
        assertEquals(Code.IDN_TEST_EXPIRED, run(TestSignIn.withToken(token(c, rsa, "RS256", "k1"))).failure());
        c.put("exp", NOW.minusSeconds(30).getEpochSecond()); // within the minute's leeway
        assertTrue(run(TestSignIn.withToken(token(c, rsa, "RS256", "k1"))).passed());
        c.remove("exp");
        assertEquals(Code.IDN_TEST_EXPIRED, run(TestSignIn.withToken(token(c, rsa, "RS256", "k1"))).failure());
        c = claims();
        c.put("nbf", NOW.plusSeconds(600).getEpochSecond());
        assertEquals(Code.IDN_TEST_EXPIRED, run(TestSignIn.withToken(token(c, rsa, "RS256", "k1"))).failure());

        c = claims();
        c.remove("groups");
        assertEquals(Code.IDN_TEST_GROUPS_CLAIM_MISSING,
                run(TestSignIn.withToken(token(c, rsa, "RS256", "k1"))).failure());
        c.put("memberOf", List.of("readers"));
        assertTrue(run(new TestSignIn(token(c, rsa, "RS256", "k1"), "memberOf")).passed());
    }

    @Test
    void reportsNeverHoldTheTokenOrClaimValues() {
        Map<String, Object> c = claims();
        c.put("groups", List.of("SECRET-GROUP-NAME"));
        c.put("aud", "api://not-it");
        String token = token(c, rsa, "RS256", "k1");
        TestReport r = run(TestSignIn.withToken(token));
        assertFalse(r.passed());
        String all = r.toString();
        assertFalse(all.contains(token));
        assertFalse(all.contains("SECRET-GROUP-NAME"));
        assertFalse(all.contains("api://not-it"));
        assertFalse(TestSignIn.withToken(token).toString().contains(token));
    }

    @Test
    void aLocalTestIssuerMayUseAnHttpKeyAddressOnThisMachine() {
        URI local = URI.create("http://127.0.0.1:8080/realms/test");
        pages.put(local + "/.well-known/openid-configuration", new Object[]{200,
                "{\"issuer\":\"" + local + "\",\"jwks_uri\":\"" + local + "/certs\"}"});
        pages.put(local + "/certs", new Object[]{200, jwks(rsaJwk(rsa, "k1"))});
        TestReport r = new OidcTestSignIn(fetch, CLOCK).run(local, AUD, TestSignIn.issuerOnly(), true);
        assertTrue(r.passed(), r.message());
        assertTrue(fetched.contains(local + "/certs local"));
        // not allowed as a local test issuer: the http key address is refused
        r = new OidcTestSignIn(fetch, CLOCK).run(local, AUD, TestSignIn.issuerOnly(), false);
        assertEquals(Code.IDN_TEST_KEYS_MISSING, r.failure());
    }

    // ---- helpers

    static KeyPair generate(String alg, String curve) {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance(alg);
            if (curve != null) g.initialize(new ECGenParameterSpec(curve));
            else g.initialize(2048);
            return g.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String token(Map<String, Object> claims, KeyPair key, String alg, String kid) {
        String header = b64("{\"alg\":\"" + alg + "\",\"kid\":\"" + kid + "\"}");
        String body = b64(json(claims));
        try {
            Signature s;
            switch (alg) {
                case "RS256" -> s = Signature.getInstance("SHA256withRSA");
                case "PS256" -> {
                    s = Signature.getInstance("RSASSA-PSS");
                    s.setParameter(new java.security.spec.PSSParameterSpec("SHA-256", "MGF1",
                            java.security.spec.MGF1ParameterSpec.SHA256, 32, 1));
                }
                case "ES256" -> s = Signature.getInstance("SHA256withECDSAinP1363Format");
                default -> throw new IllegalArgumentException(alg);
            }
            s.initSign(key.getPrivate());
            s.update((header + "." + body).getBytes(StandardCharsets.US_ASCII));
            return header + "." + body + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(s.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String rsaJwk(KeyPair k, String kid) {
        RSAPublicKey p = (RSAPublicKey) k.getPublic();
        return "{\"kty\":\"RSA\",\"use\":\"sig\",\"kid\":\"" + kid + "\",\"n\":\"" + b64(unsigned(p.getModulus()))
                + "\",\"e\":\"" + b64(unsigned(p.getPublicExponent())) + "\"}";
    }

    static String ecJwk(KeyPair k, String kid) {
        ECPublicKey p = (ECPublicKey) k.getPublic();
        return "{\"kty\":\"EC\",\"crv\":\"P-256\",\"kid\":\"" + kid + "\",\"x\":\"" + b64(fixed(p.getW().getAffineX()))
                + "\",\"y\":\"" + b64(fixed(p.getW().getAffineY())) + "\"}";
    }

    static String jwks(String... keys) {
        return "{\"keys\":[" + String.join(",", keys) + "]}";
    }

    static byte[] unsigned(BigInteger v) {
        byte[] b = v.toByteArray();
        return b.length > 1 && b[0] == 0 ? java.util.Arrays.copyOfRange(b, 1, b.length) : b;
    }

    static byte[] fixed(BigInteger v) {
        byte[] b = unsigned(v);
        byte[] out = new byte[32];
        System.arraycopy(b, 0, out, 32 - b.length, b.length);
        return out;
    }

    static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String b64(String s) {
        return b64(s.getBytes(StandardCharsets.UTF_8));
    }

    static String json(Object v) {
        if (v instanceof String s) return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        if (v instanceof Number || v instanceof Boolean) return String.valueOf(v);
        if (v instanceof List<?> l) {
            List<String> parts = new ArrayList<>();
            for (Object o : l) parts.add(json(o));
            return "[" + String.join(",", parts) + "]";
        }
        if (v instanceof Map<?, ?> m) {
            List<String> parts = new ArrayList<>();
            m.forEach((k, val) -> parts.add(json(String.valueOf(k)) + ":" + json(val)));
            return "{" + String.join(",", parts) + "}";
        }
        throw new IllegalArgumentException();
    }
}
