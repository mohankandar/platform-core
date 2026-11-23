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

    /** A ticket for {@code ref}, as only FieldValues makes one (to test that a store refuses a wrong one). */
    public static EraseTicket ticketFor(RecordRef ref) {
        return new EraseTicket(ref);
    }
}
