package com.documentplatform.fields.values;

import java.util.List;

/** What a {@link ValueStore} search compares, built by {@link FieldValues#search}. {@code toString()} shows no term. */
public sealed interface SearchMatch {

    /** The search form equals one of these. */
    record Exact(List<String> values) implements SearchMatch {
        public Exact {
            values = List.copyOf(values);
        }

        @Override
        public String toString() {
            return "Exact[" + values.size() + "]";
        }
    }

    /** The search form starts with this. */
    record Prefix(String start) implements SearchMatch {
        @Override
        public String toString() {
            return "Prefix[hidden]";
        }
    }

    /** The search form contains this. */
    record Partial(String part) implements SearchMatch {
        @Override
        public String toString() {
            return "Partial[hidden]";
        }
    }

    /** The fingerprint equals one of these (one per usable fingerprint key version). */
    record Fingerprints(List<String> fingerprints) implements SearchMatch {
        public Fingerprints {
            fingerprints = List.copyOf(fingerprints);
        }

        @Override
        public String toString() {
            return "Fingerprints[" + fingerprints.size() + "]";
        }
    }
}
