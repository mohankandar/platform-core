package com.documentplatform.identity.signin;

import com.documentplatform.identity.signin.TestReport.Code;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The test sign-in of a login connection (OIDC): before a draft can be activated, the IT Admin proves that the
 * issuer answers and, optionally, that a real token from it fits the product.
 * <ol>
 *   <li>Fetches {@code <issuer>/.well-known/openid-configuration} through the {@link IssuerFetch} (in production the
 *       platform's outbound rules, platform-outbound) and checks that its {@code issuer} is exactly the connection's.</li>
 *   <li>Fetches the key set at its {@code jwks_uri} (same rules; https, or http on this machine only for an allowed
 *       local test issuer) and checks it holds at least one usable signing key (RSA or EC).</li>
 *   <li>With a token: the signature (RS256/384/512, PS256/384/512, ES256/384/512; never {@code none} or a shared
 *       secret), {@code iss}, {@code aud} naming the product's API audience, {@code exp} (and {@code nbf}) with one
 *       minute's leeway, and the groups claim present.</li>
 * </ol>
 * The first failure ends the test with its own code ({@link Code}). Nothing is stored; the token is never logged or
 * shown back, and messages never hold a claim value or key.
 */
public final class OidcTestSignIn {

    public static final String DISCOVERY_PATH = "/.well-known/openid-configuration";
    public static final int MAX_DISCOVERY_BYTES = 64 * 1024;
    public static final int MAX_KEYS_BYTES = 256 * 1024;
    public static final int MAX_TOKEN_CHARS = 16 * 1024;
    public static final Duration LEEWAY = Duration.ofMinutes(1);

    private final IssuerFetch fetch;
    private final Clock clock;

    public OidcTestSignIn(IssuerFetch fetch, Clock clock) {
        this.fetch = Objects.requireNonNull(fetch, "fetch");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Runs the test for one issuer and API audience.
     *
     * @param localTestIssuer the issuer is an allowed local test issuer (http on this machine under a development
     *                        licence; the caller checked {@link LocalTestIssuers})
     */
    public TestReport run(URI issuer, String apiAudience, TestSignIn input, boolean localTestIssuer) {
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(apiAudience, "apiAudience");
        Objects.requireNonNull(input, "input");
        boolean withToken = input.hasToken();

        // 1. Discovery.
        String issuerText = issuer.toString();
        URI discovery = URI.create((issuerText.endsWith("/") ? issuerText.substring(0, issuerText.length() - 1)
                : issuerText) + DISCOVERY_PATH);
        IssuerFetch.Response answer;
        try {
            answer = fetch.get(discovery, MAX_DISCOVERY_BYTES, localTestIssuer);
        } catch (IssuerFetch.Refused e) {
            return TestReport.fail(Code.IDN_TEST_ISSUER_UNREACHABLE, "The issuer's discovery document could not be "
                    + "fetched (" + e.reason() + "). Check the issuer address, and that this server may reach it.",
                    e.reason(), withToken);
        } catch (IOException | RuntimeException e) {
            return TestReport.fail(Code.IDN_TEST_ISSUER_UNREACHABLE, "The issuer's discovery document could not be "
                    + "fetched. Check the issuer address, and that this server may reach it.", "FETCH_FAILED", withToken);
        }
        if (answer.status() != 200) {
            String reason = "HTTP_" + answer.status();
            return TestReport.fail(Code.IDN_TEST_ISSUER_UNREACHABLE, "The issuer answered " + answer.status()
                    + " for its discovery document (" + DISCOVERY_PATH + "). Check the issuer address.", reason,
                    withToken);
        }
        Map<String, Object> doc;
        try {
            doc = MiniJson.object(new String(answer.body(), StandardCharsets.UTF_8));
        } catch (MiniJson.Invalid e) {
            return TestReport.fail(Code.IDN_TEST_DISCOVERY_MISMATCH, "The issuer's discovery document is not a JSON "
                    + "object.", null, withToken);
        }
        if (!issuerText.equals(doc.get("issuer"))) {
            return TestReport.fail(Code.IDN_TEST_DISCOVERY_MISMATCH, "The discovery document names another issuer "
                    + "than the connection's. Copy the issuer address exactly as the login system shows it (including "
                    + "a trailing slash, if it has one).", null, withToken);
        }

        // 2. Keys.
        if (!(doc.get("jwks_uri") instanceof String jwksText) || jwksText.isBlank()) {
            return TestReport.fail(Code.IDN_TEST_KEYS_MISSING, "The discovery document has no jwks_uri.", null,
                    withToken);
        }
        URI jwks;
        try {
            jwks = URI.create(jwksText);
        } catch (IllegalArgumentException e) {
            return TestReport.fail(Code.IDN_TEST_KEYS_MISSING, "The discovery document's jwks_uri is not an address.",
                    null, withToken);
        }
        boolean jwksLocal = LocalTestIssuers.isLocalHttp(jwks);
        if (!jwks.isAbsolute() || jwks.getHost() == null
                || !("https".equals(jwks.getScheme()) || (localTestIssuer && jwksLocal))) {
            return TestReport.fail(Code.IDN_TEST_KEYS_MISSING, "The discovery document's jwks_uri is not an https "
                    + "address.", null, withToken);
        }
        List<Jwk> keys;
        try {
            IssuerFetch.Response k = fetch.get(jwks, MAX_KEYS_BYTES, localTestIssuer && jwksLocal);
            if (k.status() != 200) {
                return TestReport.fail(Code.IDN_TEST_KEYS_MISSING, "The issuer answered " + k.status() + " for its "
                        + "key set (jwks_uri).", "HTTP_" + k.status(), withToken);
            }
            keys = keys(new String(k.body(), StandardCharsets.UTF_8));
        } catch (IssuerFetch.Refused e) {
            return TestReport.fail(Code.IDN_TEST_KEYS_MISSING, "The issuer's key set could not be fetched ("
                    + e.reason() + ").", e.reason(), withToken);
        } catch (IOException | RuntimeException e) {
            return TestReport.fail(Code.IDN_TEST_KEYS_MISSING, "The issuer's key set could not be fetched.",
                    "FETCH_FAILED", withToken);
        } catch (MiniJson.Invalid e) {
            return TestReport.fail(Code.IDN_TEST_KEYS_MISSING, "The issuer's key set is not a JSON key set.", null,
                    withToken);
        }
        if (keys.isEmpty()) {
            return TestReport.fail(Code.IDN_TEST_KEYS_MISSING, "The issuer's key set holds no usable signing key "
                    + "(RSA or EC).", null, withToken);
        }
        if (!withToken) {
            return TestReport.pass("The issuer answers: its discovery document names this issuer and its key set "
                    + "holds " + keys.size() + " signing key(s). No token was checked.", false);
        }
        return token(input, issuerText, apiAudience, keys);
    }

    // ---- token

    private TestReport token(TestSignIn input, String issuer, String audience, List<Jwk> keys) {
        String token = input.token();
        String[] parts = token.length() > MAX_TOKEN_CHARS ? new String[0] : token.split("\\.", -1);
        if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
            return unreadable();
        }
        Map<String, Object> header;
        Map<String, Object> claims;
        byte[] signature;
        try {
            header = MiniJson.object(new String(b64(parts[0]), StandardCharsets.UTF_8));
            claims = MiniJson.object(new String(b64(parts[1]), StandardCharsets.UTF_8));
            signature = b64(parts[2]);
        } catch (MiniJson.Invalid | IllegalArgumentException e) {
            return unreadable();
        }
        if (!(header.get("alg") instanceof String alg) || Alg.of(alg) == null) {
            return TestReport.fail(Code.IDN_TEST_TOKEN_SIGNATURE, "The token's signing algorithm is not accepted "
                    + "(RS256/384/512, PS256/384/512 or ES256/384/512).", null, true);
        }
        Alg a = Alg.of(alg);
        Object kid = header.get("kid");
        byte[] signed = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII);
        boolean verified = false;
        boolean candidate = false;
        for (Jwk k : keys) {
            if (!k.kty.equals(a.kty) || (kid != null && !kid.equals(k.kid)) || (k.alg != null && !k.alg.equals(alg))) {
                continue;
            }
            candidate = true;
            if (a.verify(k.key, signed, signature)) {
                verified = true;
                break;
            }
        }
        if (!verified) {
            return TestReport.fail(Code.IDN_TEST_TOKEN_SIGNATURE, candidate ? "The token's signature does not "
                    + "verify with the issuer's keys." : "The issuer's key set has no key for this token (key ID or "
                    + "algorithm). Use a token issued by this login system.", null, true);
        }
        if (!issuer.equals(claims.get("iss"))) {
            return TestReport.fail(Code.IDN_TEST_TOKEN_ISSUER, "The token was issued by another issuer than the "
                    + "connection's.", null, true);
        }
        if (!audienceNames(claims.get("aud"), audience)) {
            return TestReport.fail(Code.IDN_TEST_AUDIENCE, "The token's audience (aud) does not name the API audience "
                    + "of this connection. Use a token for this product's API, or correct the API audience.", null,
                    true);
        }
        Instant now = clock.instant();
        Long exp = seconds(claims.get("exp"));
        if (exp == null) {
            return TestReport.fail(Code.IDN_TEST_EXPIRED, "The token has no expiry (exp).", null, true);
        }
        if (now.isAfter(Instant.ofEpochSecond(exp).plus(LEEWAY))) {
            return TestReport.fail(Code.IDN_TEST_EXPIRED, "The token has expired; get a fresh one.", null, true);
        }
        Long nbf = seconds(claims.get("nbf"));
        if (nbf != null && now.plus(LEEWAY).isBefore(Instant.ofEpochSecond(nbf))) {
            return TestReport.fail(Code.IDN_TEST_EXPIRED, "The token is not valid yet (nbf); check this server's "
                    + "clock.", null, true);
        }
        Object groups = claims.get(input.groupsClaim());
        if (!(groups instanceof List<?> || groups instanceof String)) {
            return TestReport.fail(Code.IDN_TEST_GROUPS_CLAIM_MISSING, "The token has no groups claim \""
                    + safe(input.groupsClaim()) + "\". Set the login system to send the person's groups in it.", null,
                    true);
        }
        return TestReport.pass("The issuer answers and the token fits: signature, issuer, audience, expiry and the "
                + "groups claim \"" + safe(input.groupsClaim()) + "\" are right.", true);
    }

    private static TestReport unreadable() {
        return TestReport.fail(Code.IDN_TEST_TOKEN_UNREADABLE, "The text given is not a signed token (JWT: three "
                + "parts separated by dots).", null, true);
    }

    private static boolean audienceNames(Object aud, String audience) {
        if (aud instanceof String s) return s.equals(audience);
        if (aud instanceof List<?> list) return list.contains(audience);
        return false;
    }

    private static Long seconds(Object v) {
        if (v instanceof Long l) return l;
        if (v instanceof Double d && !d.isNaN() && !d.isInfinite()) return d.longValue();
        return null;
    }

    /** A claim name shown back: only harmless characters. */
    private static String safe(String s) {
        return s.replaceAll("[^A-Za-z0-9_.:/-]", "?");
    }

    private static byte[] b64(String part) {
        return Base64.getUrlDecoder().decode(part);
    }

    // ---- keys

    private record Jwk(String kty, String kid, String alg, PublicKey key) {
    }

    @SuppressWarnings("unchecked")
    private static List<Jwk> keys(String json) throws MiniJson.Invalid {
        Map<String, Object> set = MiniJson.object(json);
        if (!(set.get("keys") instanceof List<?> list)) throw new MiniJson.Invalid("no keys");
        List<Jwk> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Map<String, Object> k = (Map<String, Object>) m;
            Object use = k.get("use");
            if (use != null && !"sig".equals(use)) continue;
            String kid = k.get("kid") instanceof String s ? s : null;
            String alg = k.get("alg") instanceof String s ? s : null;
            try {
                if ("RSA".equals(k.get("kty")) && k.get("n") instanceof String n && k.get("e") instanceof String e) {
                    PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(
                            new BigInteger(1, b64(n)), new BigInteger(1, b64(e))));
                    out.add(new Jwk("RSA", kid, alg, key));
                } else if ("EC".equals(k.get("kty")) && k.get("crv") instanceof String crv
                        && k.get("x") instanceof String x && k.get("y") instanceof String y && curve(crv) != null) {
                    AlgorithmParameters p = AlgorithmParameters.getInstance("EC");
                    p.init(new ECGenParameterSpec(curve(crv)));
                    ECParameterSpec spec = p.getParameterSpec(ECParameterSpec.class);
                    PublicKey key = KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(
                            new ECPoint(new BigInteger(1, b64(x)), new BigInteger(1, b64(y))), spec));
                    out.add(new Jwk("EC", kid, alg, key));
                }
            } catch (GeneralSecurityException | IllegalArgumentException unusable) {
                // a key that cannot be read is skipped; none at all is IDN_TEST_KEYS_MISSING
            }
        }
        return out;
    }

    private static String curve(String crv) {
        return switch (crv) {
            case "P-256" -> "secp256r1";
            case "P-384" -> "secp384r1";
            case "P-521" -> "secp521r1";
            default -> null;
        };
    }

    /** The accepted signing algorithms. */
    private enum Alg {
        RS256("RSA", "SHA256withRSA", null), RS384("RSA", "SHA384withRSA", null), RS512("RSA", "SHA512withRSA", null),
        PS256("RSA", "RSASSA-PSS", "SHA-256"), PS384("RSA", "RSASSA-PSS", "SHA-384"),
        PS512("RSA", "RSASSA-PSS", "SHA-512"),
        ES256("EC", "SHA256withECDSAinP1363Format", null), ES384("EC", "SHA384withECDSAinP1363Format", null),
        ES512("EC", "SHA512withECDSAinP1363Format", null);

        final String kty;
        final String jca;
        final String pssHash;

        Alg(String kty, String jca, String pssHash) {
            this.kty = kty;
            this.jca = jca;
            this.pssHash = pssHash;
        }

        static Alg of(String name) {
            for (Alg a : values()) if (a.name().equals(name)) return a;
            return null;
        }

        boolean verify(PublicKey key, byte[] signed, byte[] signature) {
            try {
                Signature s = Signature.getInstance(jca);
                if (pssHash != null) {
                    MGF1ParameterSpec mgf = switch (pssHash) {
                        case "SHA-256" -> MGF1ParameterSpec.SHA256;
                        case "SHA-384" -> MGF1ParameterSpec.SHA384;
                        default -> MGF1ParameterSpec.SHA512;
                    };
                    int saltLength = Integer.parseInt(pssHash.substring(4)) / 8;
                    s.setParameter(new PSSParameterSpec(pssHash, "MGF1", mgf, saltLength, 1));
                }
                s.initVerify(key);
                s.update(signed);
                return s.verify(signature);
            } catch (GeneralSecurityException | RuntimeException e) {
                return false;
            }
        }
    }
}
