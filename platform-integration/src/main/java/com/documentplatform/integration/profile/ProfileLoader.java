package com.documentplatform.integration.profile;

import com.documentplatform.integration.IntegrationCode;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Reads a profile from a file the app (or the installation) names, and checks it with {@link ProfileValidator}.
 * platform-core ships no profiles: an API's owner publishes its profile next to its specification, and the
 * installation keeps a copy where the app can read it.
 */
public final class ProfileLoader {

    /** The profile file was refused; lists the problems (codes and JSON pointers, never values). */
    public static final class Refused extends RuntimeException {
        private final List<ProfileProblem> problems;

        public Refused(List<ProfileProblem> problems) {
            super("Integration profile refused: " + problems, null, false, false);
            this.problems = List.copyOf(problems);
        }

        public List<ProfileProblem> problems() {
            return problems;
        }
    }

    /** At most this many bytes are read from a profile file. */
    public static final long MAX_BYTES = 2L * 1024 * 1024;

    private ProfileLoader() {
    }

    /**
     * @throws Refused with {@code INT_PROFILE_UNREADABLE} when the file cannot be read (or is too large, or not
     *                 UTF-8), or with the validator's problems
     */
    public static IntegrationProfile load(Path file) {
        ProfileValidator.Result r = check(file);
        if (!r.valid()) throw new Refused(r.problems());
        return r.profile();
    }

    /** The validator's result for the file, without throwing for its problems. */
    public static ProfileValidator.Result check(Path file) {
        Objects.requireNonNull(file, "file");
        String text;
        try {
            if (Files.size(file) > MAX_BYTES) return unreadable("larger than 2 MiB");
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(file))).toString();
        } catch (CharacterCodingException e) {
            return unreadable("not UTF-8");
        } catch (IOException | SecurityException e) {
            return unreadable(e.getClass().getSimpleName());
        }
        if (text.startsWith("﻿")) text = text.substring(1);
        return ProfileValidator.validate(text);
    }

    private static ProfileValidator.Result unreadable(String why) {
        return new ProfileValidator.Result(null, List.of(new ProfileProblem(IntegrationCode.INT_PROFILE_UNREADABLE, "", why)));
    }
}
