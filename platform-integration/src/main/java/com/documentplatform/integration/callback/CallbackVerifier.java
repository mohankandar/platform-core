package com.documentplatform.integration.callback;

import com.documentplatform.integration.IntegrationCode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Checks a signed callback an app receives from a destination ({@code Platform-Signature} and
 * {@code Platform-Callback-Id}, the scheme of {@link CallbackSignature}). In this order: the signature (in constant
 * time, against each secret given: during a secret change the receiver accepts the old and the new one), the time
 * window (default 5 minutes either way), then repeats (an ID seen before). Only a callback that passes all three is
 * recorded as seen. Codes: {@code INT_CALLBACK_SIGNATURE}, {@code INT_CALLBACK_EXPIRED}, {@code INT_CALLBACK_REPLAYED}.
 * Nothing is logged; no result holds the body or a secret.
 */
public final class CallbackVerifier {

    /** The default time window: 5 minutes. */
    public static final Duration DEFAULT_TOLERANCE = Duration.ofMinutes(5);

    /** The callback IDs already accepted, kept at least as long as the time window. */
    public interface SeenIds {
        /**
         * Records {@code id} as seen until {@code keepUntil}; false (and nothing changes) when it was seen already.
         * Several app instances need a shared one (a database table with the ID as primary key).
         */
        boolean firstTime(String id, Instant keepUntil);

        /** One in memory (one app instance), at most {@code max} IDs, the oldest dropped first. */
        static SeenIds inMemory(int max) {
            return new InMemory(max);
        }
    }

    /** The result: ok, or the code; with the callback's ID when it could be read. */
    public record Result(IntegrationCode failure, String callbackId) {
        public boolean ok() {
            return failure == null;
        }
    }

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_.:-]{1,100}");
    private static final Pattern HEX = Pattern.compile("[0-9a-f]{64}");

    private CallbackVerifier() {
    }

    /**
     * @param headers   reads one request header by name (case as the server gives; first value), or null
     * @param body      the body's exact bytes, as received
     * @param secrets   the signing secrets accepted now (the current one, and during a change the new one)
     * @param tolerance how far the callback's time may be from {@code now}, either way
     * @param seenIds   the IDs already accepted
     */
    public static Result verify(Function<String, String> headers, byte[] body, List<String> secrets,
                                Duration tolerance, SeenIds seenIds, Instant now) {
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(seenIds, "seenIds");
        Objects.requireNonNull(now, "now");
        if (secrets == null || secrets.isEmpty()) throw new IllegalArgumentException("At least one secret");
        String id = headers.apply(CallbackSignature.ID_HEADER);
        String signature = headers.apply(CallbackSignature.SIGNATURE_HEADER);
        if (id == null || !ID.matcher(id).matches() || signature == null || signature.length() > 1000) {
            return new Result(IntegrationCode.INT_CALLBACK_SIGNATURE, null);
        }
        Long t = null;
        List<String> v1 = new ArrayList<>();
        for (String part : signature.split(",")) {
            int eq = part.indexOf('=');
            if (eq < 0) return new Result(IntegrationCode.INT_CALLBACK_SIGNATURE, id);
            String k = part.substring(0, eq).trim();
            String v = part.substring(eq + 1).trim();
            if (k.equals("t")) {
                if (t != null || !v.matches("[0-9]{1,12}")) return new Result(IntegrationCode.INT_CALLBACK_SIGNATURE, id);
                t = Long.parseLong(v);
            } else if (k.equals(CallbackSignature.SCHEME)) {
                v1.add(v.toLowerCase(java.util.Locale.ROOT));
            }
        }
        if (t == null || v1.isEmpty()) return new Result(IntegrationCode.INT_CALLBACK_SIGNATURE, id);
        boolean match = false;
        for (String secret : secrets) {
            byte[] expected = CallbackSignature.v1(secret, t, body).getBytes(StandardCharsets.US_ASCII);
            for (String given : v1) {
                // Constant time for each comparison; every pair is compared (no early exit on a match).
                boolean same = HEX.matcher(given).matches()
                        && MessageDigest.isEqual(expected, given.getBytes(StandardCharsets.US_ASCII));
                match |= same;
            }
        }
        if (!match) return new Result(IntegrationCode.INT_CALLBACK_SIGNATURE, id);
        Duration tol = tolerance == null ? DEFAULT_TOLERANCE : tolerance;
        Instant at = Instant.ofEpochSecond(t);
        if (at.isBefore(now.minus(tol)) || at.isAfter(now.plus(tol))) {
            return new Result(IntegrationCode.INT_CALLBACK_EXPIRED, id);
        }
        // Kept for twice the window: a repeat inside the window is always caught.
        if (!seenIds.firstTime(id, now.plus(tol.multipliedBy(2)))) {
            return new Result(IntegrationCode.INT_CALLBACK_REPLAYED, id);
        }
        return new Result(null, id);
    }

    /** {@link #verify} with the default 5-minute window. */
    public static Result verify(Function<String, String> headers, byte[] body, List<String> secrets, SeenIds seenIds,
                                Instant now) {
        return verify(headers, body, secrets, DEFAULT_TOLERANCE, seenIds, now);
    }

    private static final class InMemory implements SeenIds {
        private final int max;
        private final Map<String, Instant> seen = new LinkedHashMap<>();

        InMemory(int max) {
            if (max < 1) throw new IllegalArgumentException("max is at least 1");
            this.max = max;
        }

        @Override
        public synchronized boolean firstTime(String id, Instant keepUntil) {
            if (seen.containsKey(id)) return false;
            seen.put(id, keepUntil);
            Iterator<Map.Entry<String, Instant>> it = seen.entrySet().iterator();
            while (seen.size() > max && it.hasNext()) { // the oldest first
                it.next();
                it.remove();
            }
            return true;
        }
    }
}
