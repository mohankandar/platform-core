package com.documentplatform.eraselog.fields;

import com.documentplatform.eraselog.EraseEntry;
import com.documentplatform.eraselog.EraseHandler;
import com.documentplatform.eraselog.EraseHandlerException;
import com.documentplatform.eraselog.EraseRequest;
import com.documentplatform.fields.FieldDefinitionException;
import com.documentplatform.fields.FieldScope;
import com.documentplatform.fields.values.FieldValues;
import com.documentplatform.fields.values.FieldValuesError;
import com.documentplatform.fields.values.FieldValuesException;
import com.documentplatform.fields.values.LegalHolds;
import com.documentplatform.fields.values.RecordRef;
import com.documentplatform.fields.values.StoredRecord;
import com.documentplatform.fields.values.ValueStore;

import java.util.Objects;
import java.util.Optional;

/**
 * The built-in handler for kind {@value #KIND}: erases a record's case field values with platform-fields
 * ({@link FieldValues#erase}: the record, its envelope and its values). The entry's entity, product, record type and
 * record ID are the {@link RecordRef}.
 *
 * <ul>
 *   <li>{@link #check}: a record under legal hold is refused before anything is logged ({@code VALUES_LEGAL_HOLD};
 *       {@code VALUES_LEGAL_HOLD_UNKNOWN} when the check cannot answer).</li>
 *   <li>{@link #apply}: a record that is no longer there counts as {@link Applied#ALREADY_GONE}.</li>
 *   <li>{@link #verify}: {@link Verified#GONE} when the record is not stored, or the stored one was made after the
 *       erasure (the same record ID used again later); {@link Verified#READABLE} otherwise.</li>
 * </ul>
 * Other platform-fields refusals stop the erase or the replay with their own {@code VALUES_*} code.
 */
public final class CaseFieldsEraseHandler implements EraseHandler {

    public static final String KIND = "case-fields";

    private final FieldValues values;
    private final ValueStore store;
    private final LegalHolds holds;

    /**
     * @param values the customer's field values (the same {@link LegalHolds} as {@code holds})
     * @param store  the value store {@code values} uses, read by the verify step
     * @param holds  the app's legal hold check, asked before an erasure is logged
     */
    public CaseFieldsEraseHandler(FieldValues values, ValueStore store, LegalHolds holds) {
        this.values = Objects.requireNonNull(values, "values");
        this.store = Objects.requireNonNull(store, "store");
        this.holds = Objects.requireNonNull(holds, "holds");
    }

    /** Without legal holds. */
    public CaseFieldsEraseHandler(FieldValues values, ValueStore store) {
        this(values, store, LegalHolds.NONE);
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public void check(EraseRequest request) {
        RecordRef ref = ref(request);
        boolean held;
        try {
            held = holds.held(ref);
        } catch (RuntimeException e) {
            throw new EraseHandlerException(FieldValuesError.VALUES_LEGAL_HOLD_UNKNOWN, "the legal hold check "
                    + "for " + ref + " could not answer");
        }
        if (held) {
            throw new EraseHandlerException(FieldValuesError.VALUES_LEGAL_HOLD, ref + " is under legal hold");
        }
    }

    @Override
    public Applied apply(EraseEntry entry, Context ctx) {
        // SEC-5: only an erasure the erase log itself hands over (Eraser, EraseReplay) is applied; a direct call with
        // a made-up entry would erase without any log entry, and a restored backup would bring the values back.
        if (!Context.issuedFor(ctx, entry)) {
            throw new EraseHandlerException(FieldValuesError.VALUES_ERASE_OUTSIDE_LOG, "case fields are erased only "
                    + "through the erase log (Eraser, kind " + KIND + "); entry " + entry.seq() + " was not handed over "
                    + "by it");
        }
        RecordRef ref = ref(entry.request());
        try {
            values.erase(ref);
            return Applied.ERASED;
        } catch (FieldValuesException e) {
            if (e.code() == FieldValuesError.VALUES_RECORD_UNKNOWN) {
                return Applied.ALREADY_GONE;
            }
            throw new EraseHandlerException(e.code(), "case fields of " + ref + " not erased");
        }
    }

    @Override
    public Verified verify(EraseEntry entry) {
        RecordRef ref = ref(entry.request());
        Optional<StoredRecord> stored;
        try {
            stored = store.find(ref);
        } catch (FieldValuesException e) {
            throw new EraseHandlerException(e.code(), "case fields of " + ref + " could not be checked");
        }
        if (stored.isEmpty() || stored.get().createdAt().isAfter(entry.request().erasedAt())) {
            return Verified.GONE;
        }
        return Verified.READABLE;
    }

    private static RecordRef ref(EraseRequest r) {
        try {
            return RecordRef.of(FieldScope.of(r.entity(), r.product()), r.recordType(), r.recordId());
        } catch (FieldValuesException | FieldDefinitionException e) {
            throw new EraseHandlerException(FieldValuesError.VALUES_RECORD_REF_INVALID, "entry for " + r.recordType() + " "
                    + r.recordId() + " is not a case field record");
        }
    }
}
