package com.documentplatform.integration.intake;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One item of a polled source, as the app's handler gets it: after it was downloaded (streamed to a temporary file,
 * with the size limit and SHA-256) and scanned. Its meaning is the app's.
 *
 * @param integrationId the polling integration
 * @param sourceItemId  the source's stable ID of the item (the app's idempotency key)
 * @param values        the values the configuration reads from the item (name → value)
 * @param file          the document (valid only while the handler runs)
 * @param correlationId the pass's correlation ID (sent with the calls when configured)
 */
public record PolledItem(UUID integrationId, String sourceItemId, Map<String, String> values, IntakeFile file,
                         String correlationId) {

    public PolledItem {
        Objects.requireNonNull(integrationId, "integrationId");
        Objects.requireNonNull(sourceItemId, "sourceItemId");
        values = Map.copyOf(values);
        Objects.requireNonNull(file, "file");
    }

    @Override
    public String toString() {
        return "PolledItem[" + sourceItemId + "]"; // never the values
    }
}
