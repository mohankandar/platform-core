package com.documentplatform.fields.values;

/**
 * For tests of platform-fields itself: erases without the caller check of {@link FieldValues#erase}, which lets only
 * the erase log's case-fields handler erase. Test sources only; production code erases through the erase log.
 */
public final class TestErase {

    private TestErase() {
    }

    public static FieldValues.Erased erase(FieldValues values, RecordRef ref) {
        return values.eraseRecord(ref);
    }
}
