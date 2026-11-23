package com.documentplatform.integration.testkit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The EICAR anti-virus test file: harmless, and recognised by every scanner as a test virus. It is put together at
 * run time, so no source or class file (and no jar) of this project holds it and no virus scanner on a build machine
 * quarantines them. For tests only.
 */
public final class Eicar {

    /** The 68 characters, reversed: so this class does not hold the signature itself. */
    private static final String REVERSED = "*H+H$!ELIF-TSET-SURIVITNA-DRADNATS-RACIE$}7)CC7)^P(45XZP\\4[PA@%P!O5X";

    private Eicar() {
    }

    /** The test file's bytes. */
    public static byte[] bytes() {
        return new StringBuilder(REVERSED).reverse().toString().getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Whether a test file written to {@code dir} stays there: a virus scanner on the machine (for example a desktop
     * one) may remove it at once. Tests that write it to disk skip where it does not stay; CI forbids skips.
     */
    public static boolean staysOnDisk(Path dir) {
        try {
            Path p = Files.createTempFile(dir, "eicar-probe-", ".bin");
            try {
                Files.write(p, bytes());
                Thread.sleep(500);
                return Files.exists(p) && java.util.Arrays.equals(Files.readAllBytes(p), bytes());
            } finally {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // a scanner holding it; nothing to do
                }
            }
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
