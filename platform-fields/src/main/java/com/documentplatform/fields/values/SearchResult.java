package com.documentplatform.fields.values;

import java.util.List;
import java.util.Objects;

/**
 * What a search found.
 *
 * @param records the matching records, by record ID
 * @param note    {@link Note#PARTIAL_AS_PREFIX} when partial search was asked for but could only run as prefix search
 *                (no trigram index); never silent
 */
public record SearchResult(List<RecordRef> records, Note note) {

    public SearchResult {
        records = List.copyOf(records);
        Objects.requireNonNull(note, "note");
    }

    public enum Note {
        NONE,
        /** Partial search is not available in this database; this search matched the start of the value only. */
        PARTIAL_AS_PREFIX
    }
}
