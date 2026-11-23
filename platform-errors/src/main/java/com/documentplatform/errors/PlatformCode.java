package com.documentplatform.errors;

import java.util.OptionalInt;

/**
 * The common shape of every platform code (and, by implementing it or mapping to it, of an app's own codes). The rule
 * it serves: whenever something fails, say exactly what failed, why, and what to do; never "an error occurred".
 *
 * <ul>
 *   <li>{@link #code()}: the stable name, unchanged from before this standard ({@code IDN_*}, {@code INT_*},
 *       {@code OUT_*}, {@code SETUP_*}, {@code LICENCE_*}, …);</li>
 *   <li>{@link #kind()}: REFUSED, FAILED, NEEDS_PERSON or INFO;</li>
 *   <li>{@link #userMessage()}: plain and safe, saying what the person does; nothing about accounts or internals;</li>
 *   <li>{@link #cause()}: the exact cause for IT and the log; placeholders {@code {name}} for safe details only;</li>
 *   <li>{@link #fix()}: what IT does;</li>
 *   <li>{@link #supportNote()}: what support tells the customer;</li>
 *   <li>{@link #httpStatus()}: optional.</li>
 * </ul>
 *
 * <p>An enum implements it with no code of its own: {@code name()} is the enum's, and the texts are read from the
 * resource {@code <EnumName>.codes.properties} next to the enum (nested enums: {@code Outer.Inner.codes.properties}),
 * keys {@code <CODE>.kind}, {@code .message}, {@code .cause}, {@code .fix}, {@code .support}, {@code .http}, with
 * defaults for every code of the enum under {@code *.<key>}. An enum may override any method instead (for example
 * with a meaning it already has). Never in any text: passwords, keys, tokens, secret values, document content or
 * field values. Names and addresses of things are fine.
 */
public interface PlatformCode {

    /** What kind of answer the code is. */
    enum Kind {
        /** The request is not allowed (the person or the calling system can change something). */
        REFUSED,
        /** We or a dependency failed. */
        FAILED,
        /** Not a failure: the item goes to a person for review. */
        NEEDS_PERSON,
        /** Not a failure: a result to report. */
        INFO
    }

    /** The enum constant's name (an enum gets it for free). */
    String name();

    /** The stable code. Defaults to {@link #name()}; never renamed. */
    default String code() {
        return name();
    }

    default Kind kind() {
        return CodeTexts.of(this).kind();
    }

    default String userMessage() {
        return CodeTexts.of(this).text("message");
    }

    default String cause() {
        return CodeTexts.of(this).text("cause");
    }

    default String fix() {
        return CodeTexts.of(this).text("fix");
    }

    default String supportNote() {
        return CodeTexts.of(this).text("support");
    }

    default OptionalInt httpStatus() {
        return CodeTexts.of(this).http();
    }

    /** The code family: the enum's name (nested enums {@code Outer.Inner}), for the catalogue. */
    default String family() {
        return CodeTexts.family(this);
    }
}
