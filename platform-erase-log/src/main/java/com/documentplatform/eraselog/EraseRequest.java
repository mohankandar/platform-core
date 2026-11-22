package com.documentplatform.eraselog;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One erasure to log, before it is acted on. Ids only, never a value: the entry says <i>that</i> something was erased,
 * not what it said.
 *
 * @param entity     the entity the record belongs to ({@code entity_uuid})
 * @param product    the product code, 2–32 of A-Z 0-9 _ starting with a letter (as in platform-fields)
 * @param recordType the app's kind of record, 1–64 of a-z 0-9 _ . - starting with a letter (for example
 *                   {@code business_record}, {@code case})
 * @param recordId   the app's ID of the record or case, 1–200 of A-Z a-z 0-9 _ . : @ / -
 * @param kind       what was erased, app-defined, 1–40 of a-z 0-9 - starting with a letter: {@code document},
 *                   {@code redacted-copy}, {@code case-fields}, {@code export-copy}, …; a handler per kind
 * @param erasedAt   when the erasure was decided (the app's time)
 * @param by         the retention rule or the person
 * @param auditId    the ID of the app's audit record for this erasure, 1–128 of A-Z a-z 0-9 _ . : @ / -
 */
public record EraseRequest(UUID entity, String product, String recordType, String recordId, String kind,
                           Instant erasedAt, ErasedBy by, String auditId) {

    static final Pattern PRODUCT = Pattern.compile("[A-Z][A-Z0-9_]{1,31}");
    static final Pattern RECORD_TYPE = Pattern.compile("[a-z][a-z0-9_.-]{0,63}");
    static final Pattern RECORD_ID = Pattern.compile("[A-Za-z0-9_.:@/-]{1,200}");
    static final Pattern KIND = Pattern.compile("[a-z][a-z0-9-]{0,39}");
    static final Pattern ID = Pattern.compile("[A-Za-z0-9_.:@/-]{1,128}");

    /** Things that are values, not ids: an e-mail address, sealed data of platform-crypto, a fingerprint. */
    private static final Pattern VALUE_LIKE = Pattern.compile(
            "[^@]+@[^@]+\\.[A-Za-z]{2,}|(?:pcv1|pci1|pck1)\\..*|f[0-9]{1,9}\\.[A-Za-z0-9_-]{20,}|vault:v[0-9]+:.*");

    public EraseRequest {
        Objects.requireNonNull(entity, "entity");
        require("product", product, PRODUCT);
        require("recordType", recordType, RECORD_TYPE);
        require("recordId", recordId, RECORD_ID);
        require("kind", kind, KIND);
        if (erasedAt == null) {
            throw invalid("erasedAt is required");
        }
        if (by == null) {
            throw invalid("by (the rule or person) is required");
        }
        require("auditId", auditId, ID);
        notAValue("recordId", recordId);
        notAValue("auditId", auditId);
    }

    static void require(String field, String value, Pattern form) {
        if (value == null || !form.matcher(value).matches()) {
            throw invalid(field + " is missing or not of its form (" + form.pattern() + ")");
        }
    }

    /** Refuses an id field that looks like a value; the message names the field only. */
    static void notAValue(String field, String value) {
        if (VALUE_LIKE.matcher(value).matches()) {
            throw new EraseLogException(EraseLogError.ERASE_ENTRY_VALUE_REFUSED, field + " looks like a value (an "
                    + "e-mail address, sealed data or a fingerprint); the erase log holds ids only");
        }
    }

    private static EraseLogException invalid(String message) {
        return new EraseLogException(EraseLogError.ERASE_ENTRY_INVALID, message);
    }

    @Override
    public String toString() {
        return kind + " of " + recordType + " " + recordId + " (entity " + entity + ", " + product + ")";
    }
}
