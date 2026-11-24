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
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Checks a signed callback an app receives from a destination ({@code Platform-Signature} and
 * {@code Platform-Callback-Id}, the scheme of {@link CallbackSignature}). In this order: the signature (in constant
 * time, against each secret given: during a secret change the receiver accepts the old and the new one), the time
 * window (default 5 minutes either way), then repeats. Only a callback that passes all three is recorded as seen.
 * Codes: {@code INT_CALLBACK_SIGNATURE}, {@code INT_CALLBACK_EXPIRED}, {@code INT_CALLBACK_REPLAYED}. Nothing is logged;
 * no result holds the body or a secret.
 *
 * <p>P044, SEC-8: the signature ({@code v2}) covers the callback's ID, and the repeat check keys on what was signed, so
 * a captured callback sent again under a fresh ID is refused. The earlier {@code v1} signature (without the ID) is
 * accepted only with {@link Policy#acceptV1()} (off by default); a {@code v1} callback is then checked for repeats by
 * its signature, which a new ID does not change.
 */
public final class CallbackVerifier {

    /** The default time window: 5 minutes. */
    public static final Duration DEFAULT_TOLERANCE = Duration.ofMinutes(5);

    /** The callback IDs (or, for {@code v1}, signatures) already accepted, kept at least as long as the time window. */
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

    /**
     * The installation's rules for callbacks in.
     *
     * @param acceptV1  whether the earlier {@code v1} signature (without the ID) is still accepted; false by default.
     *                  Turn it on only for a sender that cannot sign {@code v2} yet, and off again once it can.
     * @param tolerance how far a callback's time may be from now, either way (null: {@link #DEFAULT_TOLERANCE})
     */
    public record Policy(boolean acceptV1, Duration tolerance) {
        /** {@code v2} only, 5 minutes. */
        public static final Policy DEFAULT = new Policy(false, DEFAULT_TOLERANCE);

        public Policy {
            tolerance = tolerance == null ? DEFAULT_TOLERANCE : tolerance;
            if (tolerance.isNegative() || tolerance.isZero()) {
                throw new IllegalArgumentException("The time window is more than zero");
            }
        }

        public Result verify(Function<String, String> headers, byte[] body, List<String> secrets, SeenIds seenIds,
                             Instant now) {
            return check(headers, body, secrets, this, seenIds, now);
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
     * {@code v2} only, with the given window.
     *
     * @param headers   reads one request header by name (case as the server gives; first value), or null
     * @param body      the body's exact bytes, as received
     * @param secrets   the signing secrets accepted now (the current one, and during a change the new one)
     * @param tolerance how far the callback's time may be from {@code now}, either way
     * @param seenIds   the IDs already accepted
     */
    public static Result verify(Function<String, String> headers, byte[] body, List<String> secrets,
                                Duration tolerance, SeenIds seenIds, Instant now) {
        return check(headers, body, secrets, new Policy(false, tolerance), seenIds, now);
    }

    /** {@link #verify} with the default 5-minute window, {@code v2} only. */
    public static Result verify(Function<String, String> headers, byte[] body, List<String> secrets, SeenIds seenIds,
                                Instant now) {
        return check(headers, body, secrets, Policy.DEFAULT, seenIds, now);
    }

    private static Result check(Function<String, String> headers, byte[] body, List<String> secrets, Policy policy,
                                SeenIds seenIds, Instant now) {
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
        List<String> v2 = new ArrayList<>();
        List<String> v1 = new ArrayList<>();
        for (String part : signature.split(",")) {
            int eq = part.indexOf('=');
            if (eq < 0) return new Result(IntegrationCode.INT_CALLBACK_SIGNATURE, id);
            String k = part.substring(0, eq).trim();
            String v = part.substring(eq + 1).trim().toLowerCase(java.util.Locale.ROOT);
            if (k.equals("t")) {
                if (t != null || !v.matches("[0-9]{1,12}")) return new Result(IntegrationCode.INT_CALLBACK_SIGNATURE, id);
                t = Long.parseLong(v);
            } else if (k.equals(CallbackSignature.SCHEME)) {
                v2.add(v);
            } else if (k.equals(CallbackSignature.SCHEME_V1)) {
                v1.add(v);
            }
        }
        if (t == null) return new Result(IntegrationCode.INT_CALLBACK_SIGNATURE, id);
        // The repeat check keys on what the signature covers: the ID for v2, the signature itself for v1.
        String seenKey = null;
        boolean v2Match = false;
        for (String secret : secrets) {
            v2Match |= anyEqual(CallbackSignature.v2(secret, t, id, body), v2);
        }
        if (v2Match) {
            seenKey = id;
        } else if (policy.acceptV1()) {
            for (String secret : secrets) {
                String expected = CallbackSignature.v1(secret, t, body);
                if (anyEqual(expected, v1)) seenKey = "v1:" + expected;
            }
        }
        if (seenKey == null) return new Result(IntegrationCode.INT_CALLBACK_SIGNATURE, id);
        Duration tol = policy.tolerance();
        Instant at = Instant.ofEpochSecond(t);
        if (at.isBefore(now.minus(tol)) || at.isAfter(now.plus(tol))) {
            return new Result(IntegrationCode.INT_CALLBACK_EXPIRED, id);
        }
        // Kept for twice the window: a repeat inside the window is always caught.
        if (!seenIds.firstTime(seenKey, now.plus(tol.multipliedBy(2)))) {
            return new Result(IntegrationCode.INT_CALLBACK_REPLAYED, id);
        }
        return new Result(null, id);
    }

    /** Constant time for each comparison; every candidate is compared (no early exit on a match). */
    private static boolean anyEqual(String expectedHex, List<String> given) {
        byte[] expected = expectedHex.getBytes(StandardCharsets.US_ASCII);
        boolean match = false;
        for (String g : given) {
            match |= HEX.matcher(g).matches() && MessageDigest.isEqual(expected, g.getBytes(StandardCharsets.US_ASCII));
        }
        return match;
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
