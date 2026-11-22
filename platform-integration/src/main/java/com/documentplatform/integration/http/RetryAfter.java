package com.documentplatform.integration.http;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** Reads a Retry-After header: seconds, or an HTTP date. Passed through as it is; null when absent or unreadable. */
public final class RetryAfter {

    private RetryAfter() {
    }

    public static Duration parse(String header, Instant now) {
        if (header == null || header.isBlank()) return null;
        String v = header.trim();
        if (v.matches("\\d{1,9}")) {
            return Duration.ofSeconds(Long.parseLong(v));
        }
        try {
            Duration d = Duration.between(now, ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
            return d.isNegative() ? Duration.ZERO : d;
        } catch (DateTimeParseException unreadable) {
            return null;
        }
    }
}
