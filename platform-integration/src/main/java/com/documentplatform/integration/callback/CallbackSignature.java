package com.documentplatform.integration.callback;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

/**
 * The signature of a callback (P044, SEC-8, version 2): {@code Platform-Signature: t=<unix seconds>,v2=<hex>} where the
 * value is HMAC-SHA256 keyed with the signing secret's UTF-8 bytes over {@code t + "." + <callback ID> + "." + body}
 * (the time, a full stop, the {@code Platform-Callback-Id} header's value, a full stop, the body's exact bytes), in
 * lower-case hex. The ID is the same for every attempt of one callback, so a receiver ignores repeats; because it is
 * signed, a captured callback cannot be sent again under a new ID.
 *
 * <p>Version 1 ({@code v1}, the HMAC over {@code t + "." + body} only, without the ID) is no longer sent. A receiver
 * accepts it only when its installation turns that on ({@link CallbackVerifier.Policy#acceptV1()}).
 */
public final class CallbackSignature {

    public static final String SIGNATURE_HEADER = "Platform-Signature";
    public static final String ID_HEADER = "Platform-Callback-Id";
    /** The signature scheme sent: {@code v2}. */
    public static final String SCHEME = "v2";
    /** The earlier scheme, without the ID: accepted only when turned on. */
    public static final String SCHEME_V1 = "v1";

    private CallbackSignature() {
    }

    /** The {@code v2} value: lower-case hex HMAC-SHA256 of {@code t + "." + id + "." + body}. */
    public static String v2(String secret, long t, String id, byte[] body) {
        return hmac(secret, (t + "." + id + ".").getBytes(StandardCharsets.UTF_8), body);
    }

    /** The {@code v1} value: lower-case hex HMAC-SHA256 of {@code t + "." + body}. For verifying old callbacks only. */
    public static String v1(String secret, long t, byte[] body) {
        return hmac(secret, (t + ".").getBytes(StandardCharsets.US_ASCII), body);
    }

    /** The whole header value, {@code t=<t>,v2=<hex>}. */
    public static String header(String secret, long t, String id, byte[] body) {
        return "t=" + t + "," + SCHEME + "=" + v2(secret, t, id, body);
    }

    private static String hmac(String secret, byte[] prefix, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(prefix);
            mac.update(body);
            return HexFormat.of().formatHex(mac.doFinal());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable");
        }
    }
}
