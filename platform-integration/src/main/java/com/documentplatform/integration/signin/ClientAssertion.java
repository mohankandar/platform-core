package com.documentplatform.integration.signin;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A client's certificate credential: proves the client to the token address with a short-lived JWT (RS256) signed by
 * the certificate's private key, the client assertion of RFC 7523 ({@code iss} = {@code sub} = client ID, {@code aud}
 * = the token address, {@code x5t} and {@code x5t#S256} = the certificate's thumbprints). Generalised from the
 * notifications module's Microsoft 365 code. The private key is used here and kept nowhere; nothing here is logged.
 */
final class ClientAssertion {

    /** The PEM text is not a certificate with its matching RSA private key. */
    static final class Unusable extends Exception {
        Unusable() {
            super("certificate credential not usable", null, false, false);
        }
    }

    /** The certificate has expired. */
    static final class Expired extends Exception {
        Expired() {
            super("certificate credential expired", null, false, false);
        }
    }

    static final Duration LIFETIME = Duration.ofMinutes(5);

    private static final Pattern CERT = Pattern.compile(
            "-----BEGIN CERTIFICATE-----([A-Za-z0-9+/=\\s]+)-----END CERTIFICATE-----");
    private static final Pattern KEY = Pattern.compile(
            "-----BEGIN PRIVATE KEY-----([A-Za-z0-9+/=\\s]+)-----END PRIVATE KEY-----");
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

    private ClientAssertion() {
    }

    /** @param pem certificate and unencrypted PKCS#8 private key, in either order */
    static String create(String pem, String clientId, String audience, Instant now) throws Unusable, Expired {
        X509Certificate cert;
        PrivateKey key;
        try {
            Matcher c = CERT.matcher(pem);
            Matcher k = KEY.matcher(pem);
            if (!c.find() || !k.find()) throw new Unusable();
            cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(Base64.getMimeDecoder().decode(c.group(1))));
            key = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getMimeDecoder().decode(k.group(1))));
            if (!(cert.getPublicKey() instanceof RSAPublicKey pub) || !(key instanceof RSAPrivateKey priv)
                    || !pub.getModulus().equals(priv.getModulus())) {
                throw new Unusable();
            }
        } catch (GeneralSecurityException | IllegalArgumentException | ClassCastException e) {
            throw new Unusable();
        }
        // Only expiry is checked: a server clock a little behind must not refuse a certificate made a minute ago.
        if (!now.isBefore(cert.getNotAfter().toInstant())) {
            throw new Expired();
        }
        try {
            String x5t = URL.encodeToString(MessageDigest.getInstance("SHA-1").digest(cert.getEncoded()));
            String x5t256 = URL.encodeToString(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
            String header = "{\"alg\":\"RS256\",\"typ\":\"JWT\",\"x5t\":\"" + x5t + "\",\"x5t#S256\":\"" + x5t256
                    + "\"}";
            long iat = now.getEpochSecond();
            String claims = "{\"aud\":\"" + json(audience) + "\",\"iss\":\"" + json(clientId) + "\",\"sub\":\""
                    + json(clientId) + "\",\"jti\":\"" + UUID.randomUUID() + "\",\"nbf\":" + iat + ",\"iat\":" + iat
                    + ",\"exp\":" + (iat + LIFETIME.toSeconds()) + "}";
            String signingInput = URL.encodeToString(header.getBytes(StandardCharsets.UTF_8)) + "."
                    + URL.encodeToString(claims.getBytes(StandardCharsets.UTF_8));
            Signature rs256 = Signature.getInstance("SHA256withRSA");
            rs256.initSign(key);
            rs256.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + URL.encodeToString(rs256.sign());
        } catch (GeneralSecurityException e) {
            throw new Unusable();
        }
    }

    private static String json(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
