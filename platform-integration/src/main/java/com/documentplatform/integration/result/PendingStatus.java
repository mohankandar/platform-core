package com.documentplatform.integration.result;

import com.documentplatform.integration.json.Json;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Work a destination accepted for later (202 Accepted): what the app keeps to ask for the status later with
 * {@code CallEngine.checkStatus}. The app owns the schedule: it asks at or after {@link #nextCheckAt}, once per
 * check. Holds the destination's reference and the values read so far, never a secret or a document's content.
 *
 * @param integrationId which integration
 * @param configVersion the configuration version the request was sent with
 * @param step          the step whose request was accepted
 * @param reference     the destination's status reference (from the answer, or its {@code Location} header)
 * @param nextCheckAt   the earliest time to ask again (the destination's Retry-After, or the configured wait)
 * @param correlationId the correlation ID of the attempt, when the configuration sends one, else null
 * @param values        the values read by the attempt's steps (for the status path's step placeholders)
 */
public record PendingStatus(UUID integrationId, int configVersion, String step, String reference, Instant nextCheckAt,
                            String correlationId, Map<String, String> values) {

    /** The stored form's format name. */
    public static final String FORMAT = "platform-integration-pending/1";

    public PendingStatus {
        Objects.requireNonNull(integrationId, "integrationId");
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(nextCheckAt, "nextCheckAt");
        if (reference.isEmpty() || reference.length() > 2000 || reference.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
            throw new IllegalArgumentException("A status reference is 1-2000 characters without control characters");
        }
        values = values == null ? Map.of() : Map.copyOf(values);
    }

    /** The same, to check again at {@code at}. */
    public PendingStatus nextCheckAt(Instant at) {
        return new PendingStatus(integrationId, configVersion, step, reference, at, correlationId, values);
    }

    /** One JSON object ({@value #FORMAT}), for the app to store with its case. */
    public String toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", FORMAT);
        m.put("integrationId", integrationId.toString());
        m.put("configVersion", (long) configVersion);
        m.put("step", step);
        m.put("reference", reference);
        m.put("nextCheckAt", nextCheckAt.toString());
        if (correlationId != null) m.put("correlationId", correlationId);
        m.put("values", new LinkedHashMap<>(new java.util.TreeMap<>(values)));
        return Json.write(m);
    }

    /** Reads {@link #toJson()}'s form. */
    public static PendingStatus fromJson(String text) {
        try {
            Map<String, Object> m = Json.object(text);
            if (!FORMAT.equals(m.get("format"))) throw new IllegalArgumentException("Not " + FORMAT);
            Map<String, String> values = new LinkedHashMap<>();
            Object v = m.get("values");
            if (v instanceof Map<?, ?> vm) vm.forEach((k, x) -> values.put((String) k, (String) x));
            return new PendingStatus(UUID.fromString((String) m.get("integrationId")),
                    ((Long) m.get("configVersion")).intValue(), (String) m.get("step"), (String) m.get("reference"),
                    Instant.parse((String) m.get("nextCheckAt")), (String) m.get("correlationId"), values);
        } catch (Json.Invalid | RuntimeException e) {
            throw new IllegalArgumentException("Not a readable " + FORMAT + " document");
        }
    }

    @Override
    public String toString() {
        return "PendingStatus[integration " + integrationId + " v" + configVersion + ", step " + step + ", next check "
                + nextCheckAt + "]"; // not the reference: it may be an address
    }
}
