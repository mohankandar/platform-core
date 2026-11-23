package com.documentplatform.integration.intake;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The app's answer for one incoming item or drop. platform-core acts on it and nothing else: it acknowledges (polling)
 * or archives (watched folder) after {@link Kind#TAKEN} or {@link Kind#ALREADY_TAKEN}; after {@link Kind#REFUSED} it
 * does not acknowledge, and a drop goes to {@code _problem} with the app's code.
 *
 * @param kind what the app did
 * @param code the app's own code for a refusal (1–40 of {@code A-Z a-z 0-9 _ . : -}), else null; never a value
 */
public record IntakeAnswer(Kind kind, String code) {

    public enum Kind {
        /** The app took it now. */
        TAKEN,
        /** The app had already taken it (its own idempotency, by the source's ID): nothing new was made. */
        ALREADY_TAKEN,
        /** The app does not take it; {@code code} says why. */
        REFUSED
    }

    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_.:-]{1,40}");

    public static final IntakeAnswer TAKEN = new IntakeAnswer(Kind.TAKEN, null);
    public static final IntakeAnswer ALREADY_TAKEN = new IntakeAnswer(Kind.ALREADY_TAKEN, null);

    public IntakeAnswer {
        Objects.requireNonNull(kind, "kind");
        if (kind == Kind.REFUSED) {
            if (code == null || !CODE.matcher(code).matches()) {
                throw new IllegalArgumentException("A refusal has the app's code: 1-40 of A-Z a-z 0-9 _ . : -");
            }
        } else if (code != null) {
            throw new IllegalArgumentException("Only a refusal has a code");
        }
    }

    public static IntakeAnswer refused(String code) {
        return new IntakeAnswer(Kind.REFUSED, code);
    }

    /** Whether the app has it (taken now or before). */
    public boolean has() {
        return kind != Kind.REFUSED;
    }
}
