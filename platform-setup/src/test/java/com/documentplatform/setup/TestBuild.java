package com.documentplatform.setup;

import com.documentplatform.licensing.LicenceBuild;
import com.documentplatform.licensing.TrustedKeys;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

/** Stands for the product's setup wrapper: a development build whose keys are the file after "--keys" of a test. */
final class TestBuild {

    private TestBuild() {
    }

    /** Takes "--keys FILE" out of the arguments (it is no option) and makes the build that trusts that file's keys. */
    static Supplier<LicenceBuild> take(List<String> args) {
        int at = args.indexOf("--keys");
        if (at < 0) {
            return LicenceBuild::load;
        }
        args.remove(at);
        Path file = Path.of(args.remove(at));
        return () -> {
            try {
                return LicenceBuild.of(TrustedKeys.parse(Files.readString(file)), true, Instant.EPOCH);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }
}
