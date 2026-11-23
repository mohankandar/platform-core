package com.documentplatform.fields.values;

import java.util.Objects;

/**
 * The permission to delete one record from a {@link ValueStore} (P037, xc-public-api-2). Only {@link FieldValues}
 * makes one, inside an erasure that came through the erase log; its constructor is not public, so an app holding a
 * store cannot delete a record around the erase log. A store deletes only with a ticket for that very record.
 */
public final class EraseTicket {

    private final RecordRef ref;

    EraseTicket(RecordRef ref) {
        this.ref = Objects.requireNonNull(ref, "ref");
    }

    /** Throws {@code VALUES_ERASE_OUTSIDE_LOG} unless this ticket was made for {@code record}. */
    public static void check(EraseTicket ticket, StoredRecord record) {
        if (ticket == null || !ticket.ref.equals(record.ref())) {
            throw new FieldValuesException(FieldValuesError.VALUES_ERASE_OUTSIDE_LOG, "Record " + record.ref()
                    + " was not erased: records are deleted only by FieldValues, through the erase log");
        }
    }

    @Override
    public String toString() {
        return "EraseTicket[" + ref + "]";
    }
}
