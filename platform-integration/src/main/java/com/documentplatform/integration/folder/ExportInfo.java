package com.documentplatform.integration.folder;

import com.documentplatform.identity.EntityUuid;
import com.documentplatform.integration.json.Json;

import java.util.Map;
import java.util.Objects;

/**
 * What the app adds to a folder export's manifest besides the delivery: the entity's slot ID and the app's own
 * section.
 *
 * @param entitySlotId the entity's slot ID ({@code ENT-…}); it must be the integration's entity
 * @param appSection   the manifest's {@code "app"} object as JSON text (the app's own content: for example its case
 *                     reference, redaction status, sensitivity levels), or null for {@code {}}. platform-core only
 *                     checks that it is one JSON object of at most 1 MiB and never reads it. Never secrets.
 */
public record ExportInfo(String entitySlotId, String appSection) {

    public static final int MAX_APP_SECTION = 1024 * 1024;

    public ExportInfo {
        Objects.requireNonNull(entitySlotId, "entitySlotId");
        if (!EntityUuid.isSlotId(entitySlotId)) {
            throw new IllegalArgumentException("The entity slot ID is not valid (ENT-...)");
        }
        appSection = appSection == null ? "{}" : appSection;
        if (appSection.length() > MAX_APP_SECTION) {
            throw new IllegalArgumentException("The manifest's app section is larger than 1 MiB");
        }
        try {
            Json.object(appSection);
        } catch (Json.Invalid e) {
            // The text is not repeated: it is the app's content.
            throw new IllegalArgumentException("The manifest's app section must be one JSON object");
        }
    }

    public static ExportInfo of(String entitySlotId) {
        return new ExportInfo(entitySlotId, null);
    }

    Map<String, Object> app() {
        try {
            return Json.object(appSection);
        } catch (Json.Invalid e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public String toString() {
        return "ExportInfo[" + entitySlotId + "]";
    }
}
