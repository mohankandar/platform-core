package com.documentplatform.integration.result;

import com.documentplatform.integration.IntegrationCode;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * One health check of a destination: its configured call, classified by its own rules. Codes and statuses only.
 *
 * @param outcome SENT means healthy; anything else says why not
 */
public record HealthResult(UUID integrationId, int configVersion, Outcome outcome, IntegrationCode code,
                           String destinationCode, int httpStatus, Duration retryAfter, Instant checkedAt) {

    public boolean healthy() {
        return outcome == Outcome.SENT;
    }

    /**
     * Busy (a health rule mapped the answer to BUSY, for example 503 with a busy code or Retry-After): wait
     * {@code retryAfter} when given and send fewer at once; do not pause the destination, and count no attempt.
     */
    public boolean busy() {
        return outcome == Outcome.BUSY;
    }

    /** Down: neither healthy nor busy (can't connect, a timeout, an outage, sign-in or set-up fault): pause and probe. */
    public boolean down() {
        return !healthy() && !busy();
    }
}
