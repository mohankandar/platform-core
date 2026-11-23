package com.documentplatform.integration.scan;

import com.documentplatform.integration.IntegrationCode;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * What the scanner said about one file.
 *
 * @param verdict CLEAN, INFECTED or SCAN_FAILED
 * @param threat  for INFECTED, the scanner's name of what it found (made safe: 1–100 of letters, digits and
 *                {@code _ . : / - +}), else null
 * @param code    for SCAN_FAILED, why ({@code INT_SCAN_FAILED}, {@code INT_SCAN_UNREACHABLE}, {@code
 *                INT_ADDRESS_BLOCKED}); for INFECTED {@code INT_SCAN_INFECTED}; null for CLEAN
 */
public record ScanResult(Verdict verdict, String threat, IntegrationCode code) {

    public enum Verdict {
        CLEAN,
        /** Quarantined, never handed over; an event goes to the app. */
        INFECTED,
        /** Held and scanned again at the next pass; never let through. */
        SCAN_FAILED
    }

    private static final Pattern UNSAFE = Pattern.compile("[^A-Za-z0-9_.:/+-]");
    private static final ScanResult CLEAN_RESULT = new ScanResult(Verdict.CLEAN, null, null);

    public ScanResult {
        Objects.requireNonNull(verdict, "verdict");
    }

    public static ScanResult clean() {
        return CLEAN_RESULT;
    }

    /** The threat's name as the scanner gave it, made safe (unknown when it gives none). */
    public static ScanResult infected(String threat) {
        String t = threat == null ? "" : UNSAFE.matcher(threat.strip()).replaceAll("_");
        if (t.length() > 100) t = t.substring(0, 100);
        return new ScanResult(Verdict.INFECTED, t.isEmpty() ? "unknown" : t, IntegrationCode.INT_SCAN_INFECTED);
    }

    public static ScanResult failed(IntegrationCode code) {
        return new ScanResult(Verdict.SCAN_FAILED, null, Objects.requireNonNull(code, "code"));
    }

    public boolean isClean() {
        return verdict == Verdict.CLEAN;
    }
}
