package com.documentplatform.fields.file;

import com.documentplatform.fields.FieldKey;
import com.documentplatform.fields.FieldSpec;

import java.util.List;
import java.util.Objects;

/**
 * What an import of a field list file did (or, as a preview, would do), field by field in the file's order.
 *
 * @param applied true if the fields marked CREATE were created; false for a preview
 */
public record ImportReport(boolean applied, List<Line> lines) {

    public ImportReport {
        lines = List.copyOf(lines);
    }

    public enum Outcome {
        /** The key is new here: the field is created as in the file. */
        CREATE,
        /** A field with this key and type exists and is left as it is (see {@link Line#differences()}). */
        UNCHANGED,
        /** A field with this key exists but holds another kind of value: left as it is, never changed. */
        CONFLICT
    }

    /** Why a field of the file conflicts with the one here (P037: a {@code PlatformCode}, in the catalogue). */
    public enum Conflict implements com.documentplatform.errors.PlatformCode {
        /** The existing field has another type. */
        TYPE_DIFFERS,
        /** Both are MONEY, in different currencies. */
        CURRENCY_DIFFERS,
        /** The existing field's key differs only in capitals. */
        KEY_CASE_DIFFERS
    }

    /**
     * One field of the file.
     *
     * @param spec        the field as in the file
     * @param outcome     what happened
     * @param conflict    for CONFLICT: why
     * @param differences for UNCHANGED: what the file has differently (for example {@code label}, {@code options});
     *                    the existing field keeps its own
     * @param message     plain text for the admin
     */
    public record Line(FieldKey key, FieldSpec spec, Outcome outcome, Conflict conflict, List<String> differences,
                       String message) {
        public Line {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(outcome, "outcome");
            differences = List.copyOf(differences);
            if ((outcome == Outcome.CONFLICT) != (conflict != null)) {
                throw new IllegalArgumentException("A conflict, and only a conflict, has its reason");
            }
        }
    }

    public List<Line> created() {
        return lines.stream().filter(l -> l.outcome == Outcome.CREATE).toList();
    }

    public List<Line> unchanged() {
        return lines.stream().filter(l -> l.outcome == Outcome.UNCHANGED).toList();
    }

    public List<Line> conflicts() {
        return lines.stream().filter(l -> l.outcome == Outcome.CONFLICT).toList();
    }

    /** The specs to create. */
    public List<FieldSpec> toCreate() {
        return created().stream().map(Line::spec).toList();
    }
}
