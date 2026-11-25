package com.documentplatform.fields.values;

import com.documentplatform.fields.FieldKey;
import com.documentplatform.fields.ValueProblem;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * A value storage call was refused or failed; {@link #code()} says why. The message never holds a value (problems
 * name the field's label and rule only). No cause is attached, so nothing a driver quoted can leak through a stack
 * trace. Key service failures are not wrapped: they arrive as {@code CryptoException} with their own code.
 */
public final class FieldValuesException extends RuntimeException implements com.documentplatform.errors.HasPlatformCode {

    private final FieldValuesError code;
    private final List<ValueProblem> problems;
    private final List<FieldKey> conflictingKeys;

    public FieldValuesException(FieldValuesError code, String message) {
        this(code, message, List.of());
    }

    public FieldValuesException(FieldValuesError code, String message, List<ValueProblem> problems) {
        this(code, message, problems, List.of());
    }

    private FieldValuesException(FieldValuesError code, String message, List<ValueProblem> problems,
                                 List<FieldKey> conflictingKeys) {
        super(Objects.requireNonNull(code, "code") + ": " + message, null, false, true);
        this.code = code;
        this.problems = List.copyOf(problems);
        this.conflictingKeys = List.copyOf(conflictingKeys);
    }

    /** {@code VALUES_CONFLICT} with the keys that differ. */
    static FieldValuesException conflict(String message, Collection<FieldKey> keys) {
        return new FieldValuesException(FieldValuesError.VALUES_CONFLICT, message, List.of(), List.copyOf(keys));
    }

    /**
     * For {@link FieldValuesError#VALUES_CONFLICT}: the keys of the fields whose stored value differs from the one
     * given. Empty for every other code.
     */
    public List<FieldKey> conflictingKeys() {
        return conflictingKeys;
    }

    public FieldValuesError code() {
        return code;
    }

    /** The code, for monitoring and the app's error handler ({@code HasPlatformCode}). */
    @Override
    public com.documentplatform.errors.PlatformCode platformCode() {
        return code;
    }

    /** For {@link FieldValuesError#VALUES_INVALID}: each refused value, with its own code. */
    public List<ValueProblem> problems() {
        return problems;
    }
}
