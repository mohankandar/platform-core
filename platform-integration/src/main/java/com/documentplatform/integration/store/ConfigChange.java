package com.documentplatform.integration.store;

import com.documentplatform.integration.IntegrationCode;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One entry of a configuration's history, for the app's audit: which version, who, when, which fields changed (by
 * name only; a secret reference shows only as its field name) and the warnings given when it was saved.
 *
 * @param changedFields for version 1, every field; afterwards only those that differ from the previous version
 */
public record ConfigChange(UUID integrationId, UUID entity, String product, int version, String changedBy,
                           Instant changedAt, List<String> changedFields, List<IntegrationCode> warnings) {

    public ConfigChange {
        Objects.requireNonNull(integrationId, "integrationId");
        Objects.requireNonNull(changedBy, "changedBy");
        Objects.requireNonNull(changedAt, "changedAt");
        changedFields = List.copyOf(changedFields);
        warnings = List.copyOf(warnings);
    }
}
