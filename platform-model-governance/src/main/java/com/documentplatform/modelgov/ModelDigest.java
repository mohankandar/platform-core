package com.documentplatform.modelgov;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A model's exact digest: an algorithm and a value (lowercase hex), for example the model file's SHA-256 or the model
 * server's manifest digest. Shown as {@code algorithm:value}.
 */
public record ModelDigest(String algorithm, String value) {

    private static final Pattern ALG = Pattern.compile("[a-z0-9][a-z0-9+._-]{0,31}");
    private static final Pattern HEX = Pattern.compile("[0-9a-f]{16,128}");

    public ModelDigest {
        if (algorithm == null || !ALG.matcher(algorithm).matches()) throw Values.invalid("digest.algorithm",
                "a lowercase algorithm name such as sha256");
        if (value == null || !HEX.matcher(value).matches()) throw Values.invalid("digest.value",
                "16 to 128 lowercase hex characters");
    }

    /** From {@code algorithm:value} (as a model server's manifest gives it, for example {@code sha256:ab12…}). */
    public static ModelDigest parse(String s) {
        int c = s == null ? -1 : s.indexOf(':');
        if (c <= 0) throw Values.invalid("digest", "algorithm:value");
        return new ModelDigest(s.substring(0, c).toLowerCase(Locale.ROOT), s.substring(c + 1).toLowerCase(Locale.ROOT));
    }

    /** The SHA-256 of a model file, read as a stream. */
    public static ModelDigest sha256Of(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            return new ModelDigest("sha256", HexFormat.of().formatHex(md.digest()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public String toString() {
        return algorithm + ":" + value;
    }
}
