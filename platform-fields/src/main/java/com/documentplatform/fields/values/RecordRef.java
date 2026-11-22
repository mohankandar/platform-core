package com.documentplatform.fields.values;

import com.documentplatform.fields.FieldScope;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The app's record that field values belong to: a business record or case of one entity and product, named by the
 * app's own record type and ID. Many documents, packages or submissions of the app may point to one record.
 *
 * @param scope      the entity and product
 * @param recordType the app's kind of record, 1–64 of a-z 0-9 _ . - starting with a letter (for example
 *                   {@code business_record})
 * @param recordId   the app's ID of the record, 1–200 of A-Z a-z 0-9 _ . : @ / - (for example its UUID)
 */
public record RecordRef(FieldScope scope, String recordType, String recordId) {

    private static final Pattern TYPE = Pattern.compile("[a-z][a-z0-9_.-]{0,63}");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_.:@/-]{1,200}");

    public RecordRef {
        Objects.requireNonNull(scope, "scope");
        requireType(recordType);
        if (recordId == null || !ID.matcher(recordId).matches()) {
            throw new FieldValuesException(FieldValuesError.VALUES_RECORD_REF_INVALID,
                    "A record ID has 1 to 200 of A-Z a-z 0-9 _ . : @ / -");
        }
    }

    public static RecordRef of(FieldScope scope, String recordType, String recordId) {
        return new RecordRef(scope, recordType, recordId);
    }

    static String requireType(String recordType) {
        if (recordType == null || !TYPE.matcher(recordType).matches()) {
            throw new FieldValuesException(FieldValuesError.VALUES_RECORD_REF_INVALID,
                    "A record type has 1 to 64 of a-z 0-9 _ . - and starts with a letter");
        }
        return recordType;
    }

    @Override
    public String toString() {
        return recordType + " " + recordId + " (" + scope + ")";
    }
}
