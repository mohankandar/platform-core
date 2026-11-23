package com.documentplatform.integration.callback;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

/**
 * The signature of a callback: {@code Platform-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256(secret, t + "." +
 * body)>}, the HMAC over the time, a full stop and the body's exact bytes, keyed with the signing secret's UTF-8
 * bytes, in lower-case hex. With it, {@code Platform-Callback-Id: <unique id>}, the same for every attempt of one
 * callback, so a receiver can ignore repeats.
 */
public final class CallbackSignature {

    public static final String SIGNATURE_HEADER = "Platform-Signature";
    public static final String ID_HEADER = "Platform-Callback-Id";
    /** The signature scheme's name in the header. */
    public static final String SCHEME = "v1";

    private CallbackSignature() {
    }

    /** The {@code v1} value: lower-case hex HMAC-SHA256 of {@code t + "." + body}. */
    public static String v1(String secret, long t, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((t + ".").getBytes(StandardCharsets.US_ASCII));
            mac.update(body);
            return HexFormat.of().formatHex(mac.doFinal());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable");
        }
    }

    /** The whole header value, {@code t=<t>,v1=<hex>}. */
    public static String header(String secret, long t, byte[] body) {
        return "t=" + t + "," + SCHEME + "=" + v1(secret, t, body);
    }
}
