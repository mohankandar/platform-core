package com.documentplatform.fields.file;

import com.documentplatform.fields.FieldDefinition;
import com.documentplatform.fields.FieldSpec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Matches a file's fields to the existing ones by key. A new key is created; an existing key is never changed:
 * UNCHANGED if it holds the same kind of value (type and currency), CONFLICT otherwise.
 */
public final class FieldImport {

    private FieldImport() {
    }

    /**
     * @param existing the entity's fields, switched-off ones included
     * @param incoming the file's fields
     * @param applied  whether the report is for an import that creates (true) or a preview (false)
     */
    public static ImportReport plan(List<FieldDefinition> existing, List<FieldSpec> incoming, boolean applied) {
        Map<String, FieldSpec> byFolded = new HashMap<>();
        for (FieldDefinition d : existing) {
            byFolded.put(d.key().folded(), d.spec());
        }
        List<ImportReport.Line> lines = new ArrayList<>();
        for (FieldSpec in : incoming) {
            FieldSpec here = byFolded.get(in.key().folded());
            if (here == null) {
                lines.add(new ImportReport.Line(in.key(), in, ImportReport.Outcome.CREATE, null, List.of(),
                        in.label() + " (" + in.key() + ") " + (applied ? "created" : "will be created")));
            } else if (!here.key().equals(in.key())) {
                lines.add(new ImportReport.Line(in.key(), in, ImportReport.Outcome.CONFLICT,
                        ImportReport.Conflict.KEY_CASE_DIFFERS, List.of(), "The key " + in.key() + " differs only in "
                        + "capitals from the existing field " + here.key() + "; nothing changed"));
            } else if (here.type() != in.type()) {
                lines.add(new ImportReport.Line(in.key(), in, ImportReport.Outcome.CONFLICT,
                        ImportReport.Conflict.TYPE_DIFFERS, List.of(), "Field " + in.key() + " is " + here.type()
                        + " here and " + in.type() + " in the file; the existing field keeps its type"));
            } else if (!Objects.equals(here.currency(), in.currency())) {
                lines.add(new ImportReport.Line(in.key(), in, ImportReport.Outcome.CONFLICT,
                        ImportReport.Conflict.CURRENCY_DIFFERS, List.of(), "Field " + in.key() + " is in "
                        + here.currency() + " here and in " + in.currency() + " in the file; the existing field "
                        + "keeps its currency"));
            } else {
                List<String> differences = differences(here, in);
                lines.add(new ImportReport.Line(in.key(), in, ImportReport.Outcome.UNCHANGED, null, differences,
                        differences.isEmpty() ? "Field " + in.key() + " is already the same"
                                : "Field " + in.key() + " exists and is left as it is (the file differs in "
                                + String.join(", ", differences) + ")"));
            }
        }
        return new ImportReport(applied, lines);
    }

    /** The names of the members two specs of the same key and type differ in, in the file's member names. */
    static List<String> differences(FieldSpec a, FieldSpec b) {
        Map<String, Object> ja = FieldListFile.toJson(a);
        Map<String, Object> jb = FieldListFile.toJson(b);
        List<String> out = new ArrayList<>();
        for (String k : new java.util.TreeSet<>(union(ja, jb))) {
            if (!Objects.equals(ja.get(k), jb.get(k))) {
                out.add(k);
            }
        }
        return out;
    }

    private static java.util.Set<String> union(Map<String, Object> a, Map<String, Object> b) {
        java.util.Set<String> keys = new java.util.HashSet<>(a.keySet());
        keys.addAll(b.keySet());
        return keys;
    }
}
