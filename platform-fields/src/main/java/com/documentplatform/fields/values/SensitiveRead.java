package com.documentplatform.fields.values;

import com.documentplatform.errors.HasPlatformCode;
import com.documentplatform.errors.PlatformCode;

import java.util.Objects;
import java.util.Optional;

/**
 * One record's result of {@link FieldValues#readSensitive(java.util.List)}: its opened values, or the exception the
 * single read throws for it ({@link FieldValuesException}, for example {@code VALUES_RECORD_UNKNOWN}, or a
 * {@code CryptoException}, for example {@code CRYPTO_CIPHERTEXT_INVALID}). {@code toString()} shows no value.
 */
public final class SensitiveRead {

    private final RecordRef ref;
    private final RecordValues values;
    private final RuntimeException failure;

    private SensitiveRead(RecordRef ref, RecordValues values, RuntimeException failure) {
        this.ref = Objects.requireNonNull(ref, "ref");
        this.values = values;
        this.failure = failure;
    }

    static SensitiveRead read(RecordRef ref, RecordValues values) {
        return new SensitiveRead(ref, Objects.requireNonNull(values, "values"), null);
    }

    static SensitiveRead failed(RecordRef ref, RuntimeException failure) {
        return new SensitiveRead(ref, null, Objects.requireNonNull(failure, "failure"));
    }

    /** The record asked for. */
    public RecordRef ref() {
        return ref;
    }

    public boolean ok() {
        return failure == null;
    }

    /** The record's values, sensitive ones opened; throws what the single read throws if it was not read. */
    public RecordValues values() {
        if (failure != null) {
            throw failure;
        }
        return values;
    }

    /** What the single read throws for this record, or null when it was read. */
    public RuntimeException failure() {
        return failure;
    }

    /** The failure's code, if it was not read and the failure has one. */
    public Optional<PlatformCode> code() {
        return failure instanceof HasPlatformCode f ? Optional.of(f.platformCode()) : Optional.empty();
    }

    @Override
    public String toString() {
        return "SensitiveRead[" + ref + ", " + (failure == null ? "read"
                : code().map(Object::toString).orElse(failure.getClass().getSimpleName())) + "]";
    }
}
