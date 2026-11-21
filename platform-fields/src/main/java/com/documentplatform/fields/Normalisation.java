package com.documentplatform.fields;

import java.util.Locale;
import java.util.Objects;

/**
 * How a value is made comparable before it is checked and matched. Always first: the value is trimmed and runs of
 * spaces and tabs become one space. Then {@link Strip}, then {@link LetterCase}. Length rules and patterns apply to
 * the normalised value.
 */
public record Normalisation(LetterCase letterCase, Strip strip) {

    /** Trimming and single spaces only. */
    public static final Normalisation NONE = new Normalisation(LetterCase.PRESERVE, Strip.NONE);

    public enum LetterCase {
        PRESERVE, UPPER, LOWER
    }

    public enum Strip {
        /** Nothing removed. */
        NONE,
        /** Dashes and spaces removed ({@code 12-34 56} → {@code 123456}). */
        SEPARATORS,
        /** Everything but the digits 0–9 removed. */
        NON_DIGITS
    }

    public Normalisation {
        Objects.requireNonNull(letterCase, "letterCase");
        Objects.requireNonNull(strip, "strip");
    }

    public static Normalisation of(LetterCase letterCase, Strip strip) {
        return new Normalisation(letterCase, strip);
    }

    public boolean isNone() {
        return letterCase == LetterCase.PRESERVE && strip == Strip.NONE;
    }

    /** Trims and makes single spaces only (the part that always applies). */
    static String spaces(String value) {
        return value.strip().replaceAll("[ \\t\\u00A0]+", " ");
    }

    /** The normalised form of a value that has already passed the character check. */
    public String apply(String value) {
        String v = spaces(value);
        v = switch (strip) {
            case NONE -> v;
            case SEPARATORS -> v.replace("-", "").replace(" ", "");
            case NON_DIGITS -> v.replaceAll("[^0-9]", "");
        };
        return switch (letterCase) {
            case PRESERVE -> v;
            case UPPER -> v.toUpperCase(Locale.ROOT);
            case LOWER -> v.toLowerCase(Locale.ROOT);
        };
    }
}
