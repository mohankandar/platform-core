package com.documentplatform.fields.inmemory;

import com.documentplatform.fields.FieldKey;
import com.documentplatform.fields.FieldScope;
import com.documentplatform.fields.FieldValuesCheck;

import java.util.HashSet;
import java.util.Set;

/** A {@link FieldValuesCheck} the test controls: no values exist until {@link #valuesExistFor} says so. */
public final class TestValuesCheck implements FieldValuesCheck {

    private final Set<String> withValues = new HashSet<>();
    private volatile boolean unavailable;

    /** From now on, values exist for this field. */
    public synchronized TestValuesCheck valuesExistFor(FieldScope scope, FieldKey key) {
        withValues.add(scope + "|" + key.folded());
        return this;
    }

    /** From now on, the check fails as if the app's database were down. */
    public TestValuesCheck unavailable(boolean unavailable) {
        this.unavailable = unavailable;
        return this;
    }

    @Override
    public synchronized boolean valuesExist(FieldScope scope, FieldKey key) {
        if (unavailable) {
            throw new IllegalStateException("values check unavailable");
        }
        return withValues.contains(scope + "|" + key.folded());
    }
}
