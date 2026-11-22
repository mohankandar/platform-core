package com.documentplatform.integration.config;

import com.documentplatform.integration.IntegrationCode;
import com.documentplatform.integration.json.Json;
import com.documentplatform.integration.result.Outcome;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One classification rule, as data: an answer's status and the destination's own code (and optionally one field of
 * the answer, or whether it sent Retry-After) → the result class. Rules are tried in order; the first that matches
 * decides. For example "503 with the busy code = busy, no attempt used; 503 without it = outage; AUT-004 = sign-in
 * fault":
 *
 * <pre>
 * Rule.of("503", "DEST-BUSY", Outcome.BUSY)
 * Rule.of("503", "-", Outcome.OUTAGE)
 * Rule.of("any", "DEST-AUT-004", Outcome.SIGN_IN_FAILED)
 * Rule.of("503", null, Outcome.BUSY).whenRetryAfter()     // 503 with a Retry-After header
 * </pre>
 *
 * @param status       {@code any}, an exact status ({@code 503}), a class ({@code 5xx}) or a range ({@code 500-599})
 * @param code         the destination's code: null for any (present or not), {@code -} for none sent, a prefix
 *                     ending in {@code *} ({@code DEST-SEN-*}), or an exact code
 * @param pointer      optional: a JSON pointer into the answer, together with {@code equals}
 * @param equals       optional: the value expected there (compared as text; true, false and numbers too)
 * @param outcome      the result class when the rule matches
 * @param platformCode optional: the platform code; else the outcome's usual one
 * @param attemptUsed  optional: whether the app should count the attempt; else the outcome's default (busy: no)
 * @param retryAfter   optional: true = only when the answer has a Retry-After header, false = only without one
 */
public record Rule(String status, String code, String pointer, String equals, Outcome outcome,
                   IntegrationCode platformCode, Boolean attemptUsed, Boolean retryAfter) {

    private static final Pattern STATUS = Pattern.compile("any|[1-5]xx|[1-5]\\d\\d|[1-5]\\d\\d-[1-5]\\d\\d");
    private static final Pattern CODE = Pattern.compile("-|[A-Za-z0-9_.:-]{1,40}\\*?|\\*");

    public Rule {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(outcome, "outcome");
        if (!STATUS.matcher(status).matches()) {
            throw new IllegalArgumentException("Rule status is not valid (any, 503, 5xx or 500-599)");
        }
        if (status.contains("-")) {
            String[] r = status.split("-");
            if (Integer.parseInt(r[0]) > Integer.parseInt(r[1])) {
                throw new IllegalArgumentException("Rule status range is not valid");
            }
        }
        if ("*".equals(code)) {
            code = null;
        }
        if (code != null && !CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("Rule code is not valid");
        }
        if ((pointer == null) != (equals == null)) {
            throw new IllegalArgumentException("Rule pointer and value go together");
        }
        if (pointer != null && (!Json.isPointer(pointer) || equals.length() > 200)) {
            throw new IllegalArgumentException("Rule pointer is not valid");
        }
        if (platformCode != null && platformCode.name().startsWith("INT_CONFIG_")) {
            throw new IllegalArgumentException("Rule platform code must be an attempt code");
        }
    }

    /** Without a Retry-After condition. */
    public Rule(String status, String code, String pointer, String equals, Outcome outcome,
                IntegrationCode platformCode, Boolean attemptUsed) {
        this(status, code, pointer, equals, outcome, platformCode, attemptUsed, null);
    }

    public static Rule of(String status, String code, Outcome outcome) {
        return new Rule(status, code, null, null, outcome, null, null, null);
    }

    /** Also requires the answer's field at {@code pointer} to equal {@code value}. */
    public Rule whenField(String pointer, String value) {
        return new Rule(status, code, pointer, value, outcome, platformCode, attemptUsed, retryAfter);
    }

    /** Also requires a Retry-After header. */
    public Rule whenRetryAfter() {
        return new Rule(status, code, pointer, equals, outcome, platformCode, attemptUsed, true);
    }

    public Rule withPlatformCode(IntegrationCode c) {
        return new Rule(status, code, pointer, equals, outcome, c, attemptUsed, retryAfter);
    }

    public Rule withAttemptUsed(boolean used) {
        return new Rule(status, code, pointer, equals, outcome, platformCode, used, retryAfter);
    }

    /** Whether the rule matches an answer without a Retry-After header. */
    public boolean matches(int httpStatus, String destinationCode, Object json) {
        return matches(httpStatus, destinationCode, json, false);
    }

    /**
     * Whether the rule matches this answer. {@code json} is the parsed answer, or null when it is not JSON;
     * {@code hasRetryAfter} whether the answer had a Retry-After header.
     */
    public boolean matches(int httpStatus, String destinationCode, Object json, boolean hasRetryAfter) {
        if (!statusMatches(httpStatus)) return false;
        if (retryAfter != null && retryAfter != hasRetryAfter) return false;
        if (code != null) {
            if (code.equals("-")) {
                if (destinationCode != null) return false;
            } else if (destinationCode == null) {
                return false;
            } else if (code.endsWith("*")) {
                if (!destinationCode.startsWith(code.substring(0, code.length() - 1))) return false;
            } else if (!code.equals(destinationCode)) {
                return false;
            }
        }
        if (pointer != null) {
            Object v = json == null ? null : Json.at(json, pointer).orElse(null);
            if (v == null || v instanceof java.util.Map || v instanceof java.util.List) return false;
            return String.valueOf(v).equals(equals);
        }
        return true;
    }

    private boolean statusMatches(int s) {
        if (status.equals("any")) return true;
        if (status.endsWith("xx")) return s / 100 == status.charAt(0) - '0';
        if (status.contains("-")) {
            String[] r = status.split("-");
            return s >= Integer.parseInt(r[0]) && s <= Integer.parseInt(r[1]);
        }
        return s == Integer.parseInt(status);
    }

    /** The platform code this rule gives. */
    public IntegrationCode effectiveCode() {
        if (platformCode != null) return platformCode;
        return switch (outcome) {
            case SENT -> IntegrationCode.INT_SENT;
            case ALREADY_RECEIVED -> IntegrationCode.INT_ALREADY_RECEIVED;
            case REFUSED -> IntegrationCode.INT_REFUSED;
            case SIGN_IN_FAILED -> IntegrationCode.INT_SIGN_IN_REFUSED;
            case CANT_CONNECT -> IntegrationCode.INT_CANT_CONNECT;
            case BUSY -> IntegrationCode.INT_BUSY;
            case OUTAGE -> IntegrationCode.INT_OUTAGE;
            case UNCLEAR -> IntegrationCode.INT_UNCLEAR_ANSWER;
            case NOT_CONFIGURED -> IntegrationCode.INT_ADDRESS_NOT_FOUND;
        };
    }

    public boolean effectiveAttemptUsed() {
        return attemptUsed != null ? attemptUsed : outcome.usesAttemptByDefault();
    }
}
