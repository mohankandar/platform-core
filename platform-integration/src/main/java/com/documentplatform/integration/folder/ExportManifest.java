package com.documentplatform.integration.folder;

import com.documentplatform.integration.json.internal.Json;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The manifest of a folder export, format {@value #FORMAT}, written last as {@code manifest.json} in the package
 * folder: the common envelope (package, product, entity, integration and its configuration version, the documents
 * with their checksums, optional case field values in the {@code platform-field-values/1} wire format) and the
 * app's own {@code "app"} object, which platform-core writes as it was given and never reads. Its JSON Schema is
 * {@value #SCHEMA_RESOURCE}; the format in words is in {@code docs/design/integration-module.md}.
 *
 * <pre>{@code
 * {"format":"platform-export-manifest/1","packageId":"PKG-2026-0042","correlationId":"c-81f2…",
 *  "createdAt":"2026-11-22T14:05:09Z","product":"PRODUCT_A","entitySlotId":"ENT-0001",
 *  "integration":{"id":"6f1d…","configVersion":3},
 *  "documents":[{"fileName":"statement.pdf","sourceDocumentId":"doc-1","contentType":"application/pdf",
 *                "size":48213,"sha256":"9f86d0…"}],
 *  "fields":{"format":"platform-field-values/1","fields":[{"key":"reference_number","type":"TEXT","value":"R-42"}]},
 *  "app":{ … }}
 * }</pre>
 */
final class ExportManifest {

    public static final String FORMAT = "platform-export-manifest/1";
    public static final String SCHEMA_RESOURCE = "schema/platform-export-manifest-1.schema.json";

    /** Envelope members that differ between two attempts of the same package, so they do not count as "different". */
    static final List<String> PER_ATTEMPT = List.of("createdAt", "correlationId");

    /** One document as the manifest lists it. */
    public record Entry(String fileName, String sourceDocumentId, String contentType, long size, String sha256) {
        public Entry {
            Objects.requireNonNull(fileName, "fileName");
            Objects.requireNonNull(sourceDocumentId, "sourceDocumentId");
            Objects.requireNonNull(contentType, "contentType");
            Objects.requireNonNull(sha256, "sha256");
        }
    }

    private ExportManifest() {
    }

    /**
     * The manifest's text.
     *
     * @param fields the {@code platform-field-values/1} object (already limited to what may go), or null for none
     * @param app    the app's section, JSON text of one object, written as it is
     */
    static String write(String packageId, String correlationId, Instant createdAt, String product, String entitySlotId,
                        UUID integrationId, int configVersion, List<Entry> documents, Map<String, Object> fields,
                        String app) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", FORMAT);
        m.put("packageId", packageId);
        m.put("correlationId", correlationId);
        m.put("createdAt", createdAt.toString());
        m.put("product", product);
        m.put("entitySlotId", entitySlotId);
        Map<String, Object> integration = new LinkedHashMap<>();
        integration.put("id", integrationId.toString());
        integration.put("configVersion", (long) configVersion);
        m.put("integration", integration);
        List<Object> docs = new ArrayList<>();
        for (Entry e : documents) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("fileName", e.fileName());
            d.put("sourceDocumentId", e.sourceDocumentId());
            d.put("contentType", e.contentType());
            d.put("size", e.size());
            d.put("sha256", e.sha256());
            docs.add(d);
        }
        m.put("documents", docs);
        if (fields != null) m.put("fields", fields);
        String envelope = Json.write(m);
        // The app's section goes in as the app wrote it.
        return envelope.substring(0, envelope.length() - 1) + ",\"app\":" + app.strip() + "}";
    }

    /**
     * Whether an existing manifest is for the same package as this one: equal apart from {@link #PER_ATTEMPT}
     * ({@code createdAt}, {@code correlationId}) and the configuration version (a resend after a configuration change
     * that did not change the content). An unreadable manifest is not the same.
     */
    static boolean samePackage(String existing, String ours) {
        try {
            return comparable(Json.object(existing)).equals(comparable(Json.object(ours)));
        } catch (Json.Invalid e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> comparable(Map<String, Object> m) {
        Map<String, Object> c = new LinkedHashMap<>(m);
        PER_ATTEMPT.forEach(c::remove);
        if (c.get("integration") instanceof Map<?, ?> i) {
            Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) i);
            copy.remove("configVersion");
            c.put("integration", copy);
        }
        return c;
    }
}
