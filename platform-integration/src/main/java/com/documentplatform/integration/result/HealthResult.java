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
}
