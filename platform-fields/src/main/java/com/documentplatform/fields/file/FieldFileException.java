package com.documentplatform.fields.file;

import com.documentplatform.fields.FieldDefinitionException;

import java.util.Objects;

/**
 * A field list file cannot be read. {@link #code()} says why; for a field the file describes wrongly,
 * {@link #fieldNumber()} says which (1 = first) and {@link #fieldCode()} which rule it breaks. Messages name positions,
 * members and rules, never content.
 */
public final class FieldFileException extends IllegalArgumentException implements com.documentplatform.errors.HasPlatformCode {

    public enum Code implements com.documentplatform.errors.PlatformCode {
        /** Not JSON of the strict subset used (no null, whole numbers only, no repeated members). */
        FILE_UNREADABLE,
        /** Not a {@code platform-fields/1} file. */
        FILE_FORMAT_UNKNOWN,
        /** A member is missing, unknown or of the wrong kind. */
        FILE_SHAPE_INVALID,
        /** A field breaks a rule of the model; see {@link #fieldCode()}. */
        FILE_FIELD_INVALID,
        /** Two fields with the same key (ignoring capitals). */
        FILE_KEY_REPEATED,
        /** More than 1000 fields, or larger than 5 MB. */
        FILE_TOO_LARGE
    }

    private final Code code;
    private final Integer fieldNumber;
    private final FieldDefinitionException.Code fieldCode;

    FieldFileException(Code code, Integer fieldNumber, FieldDefinitionException.Code fieldCode, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
        this.fieldNumber = fieldNumber;
        this.fieldCode = fieldCode;
    }

    public Code code() {
        return code;
    }

    /** The code, for monitoring and the app's error handler ({@code HasPlatformCode}). */
    @Override
    public com.documentplatform.errors.PlatformCode platformCode() {
        return code;
    }

    /** Which field (1 = first), when one is at fault. */
    public Integer fieldNumber() {
        return fieldNumber;
    }

    /** For {@code FILE_FIELD_INVALID}: the rule the field breaks. */
    public FieldDefinitionException.Code fieldCode() {
        return fieldCode;
    }
}
