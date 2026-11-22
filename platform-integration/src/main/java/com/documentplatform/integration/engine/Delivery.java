package com.documentplatform.integration.engine;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What the app hands the engine for one attempt: a package of documents and the plain values its templates use. The
 * content is read only while it is sent, never kept or logged.
 *
 * @param packageId     the app's stable ID for this package ({@code {package.id}}; use it as the idempotency key)
 * @param correlationId sent as {@code X-Correlation-Id} when configured; null to make a new one for this attempt
 * @param documents     the documents, in order
 * @param values        plain values for {@code {value.<name>}} placeholders. Not for secrets or document text.
 * @param resume        where an earlier attempt stopped, or {@link Resume#NONE}
 */
public record Delivery(String packageId, String correlationId, List<Document> documents, Map<String, String> values,
                       Resume resume) {

    static final Pattern ID = Pattern.compile("[A-Za-z0-9._:-]{1,200}");
    static final Pattern CORRELATION = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Pattern VALUE_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");

    public Delivery {
        Objects.requireNonNull(packageId, "packageId");
        if (!ID.matcher(packageId).matches()) {
            throw new IllegalArgumentException("Package ID must be 1-200 of A-Z a-z 0-9 . _ : -");
        }
        if (correlationId != null && !CORRELATION.matcher(correlationId).matches()) {
            throw new IllegalArgumentException("Correlation ID must be 1-128 of A-Z a-z 0-9 . _ : -");
        }
        documents = documents == null ? List.of() : List.copyOf(documents);
        Set<String> ids = new HashSet<>();
        for (Document d : documents) {
            if (!ids.add(d.sourceDocumentId())) throw new IllegalArgumentException("Document IDs must be unique");
        }
        Map<String, String> v = new LinkedHashMap<>();
        if (values != null) {
            values.forEach((k, val) -> {
                if (k == null || !VALUE_NAME.matcher(k).matches() || val == null || val.length() > 2000) {
                    throw new IllegalArgumentException("Value names are letters, digits, '_' or '-'; values at most "
                            + "2000 characters");
                }
                v.put(k, val);
            });
        }
        values = java.util.Collections.unmodifiableMap(v);
        resume = resume == null ? Resume.NONE : resume;
    }

    public static Delivery of(String packageId, List<Document> documents, Map<String, String> values) {
        return new Delivery(packageId, null, documents, values, Resume.NONE);
    }

    public Delivery resumingFrom(Resume r) {
        return new Delivery(packageId, correlationId, documents, values, r);
    }

    /** Opens a document's content for reading; called only while the document is sent. */
    @FunctionalInterface
    public interface Content {
        InputStream open() throws IOException;
    }

    /**
     * One document.
     *
     * @param sourceDocumentId the app's stable ID for the document
     * @param fileName         its file name (1–500 characters, no control characters, no path)
     * @param contentType      its media type ({@code application/pdf})
     * @param size             its size in bytes
     * @param sha256           its SHA-256, lower-case hex
     */
    public record Document(String sourceDocumentId, String fileName, String contentType, long size, String sha256,
                           Content content) {

        private static final Pattern TYPE = Pattern.compile("[a-z0-9][a-z0-9!#$&^_.+-]{0,126}/[a-z0-9][a-z0-9!#$&^_.+-]{0,126}");
        private static final Pattern SHA = Pattern.compile("[0-9a-f]{64}");

        public Document {
            Objects.requireNonNull(sourceDocumentId, "sourceDocumentId");
            Objects.requireNonNull(fileName, "fileName");
            Objects.requireNonNull(contentType, "contentType");
            Objects.requireNonNull(sha256, "sha256");
            Objects.requireNonNull(content, "content");
            if (!ID.matcher(sourceDocumentId).matches()) {
                throw new IllegalArgumentException("Document ID must be 1-200 of A-Z a-z 0-9 . _ : -");
            }
            if (fileName.isEmpty() || fileName.length() > 500 || fileName.contains("/") || fileName.contains("\\")
                    || fileName.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
                throw new IllegalArgumentException("File name must be 1-500 characters, no path or control characters");
            }
            if (!TYPE.matcher(contentType).matches()) throw new IllegalArgumentException("Media type is not valid");
            if (size < 0) throw new IllegalArgumentException("Size cannot be negative");
            if (!SHA.matcher(sha256).matches()) throw new IllegalArgumentException("SHA-256 must be 64 lower-case hex");
        }

        /** A document held in memory; size and SHA-256 are worked out here. */
        public static Document of(String sourceDocumentId, String fileName, String contentType, byte[] bytes) {
            byte[] copy = bytes.clone();
            return new Document(sourceDocumentId, fileName, contentType, copy.length, Delivery.sha256(copy),
                    () -> new ByteArrayInputStream(copy));
        }

        @Override
        public String toString() {
            return "Document[" + sourceDocumentId + ", " + contentType + ", " + size + " bytes]";
        }
    }

    /**
     * Where an earlier attempt stopped, so the next one carries on: the steps run once that are done, the values they
     * read, and the documents already through their per-document steps.
     */
    public record Resume(Set<String> stepsDone, Map<String, String> values, Set<String> documentsDone) {
        public static final Resume NONE = new Resume(Set.of(), Map.of(), Set.of());

        public Resume {
            stepsDone = Set.copyOf(stepsDone);
            values = Map.copyOf(values);
            documentsDone = Set.copyOf(documentsDone);
        }

        public static Resume from(com.documentplatform.integration.result.AttemptResult r) {
            return new Resume(r.stepsDone(), r.values(), r.documentsDone());
        }
    }

    static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
