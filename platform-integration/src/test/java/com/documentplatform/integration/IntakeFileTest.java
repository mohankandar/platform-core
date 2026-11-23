package com.documentplatform.integration;

import com.documentplatform.integration.intake.IntakeFile;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/**
 * integration-module-129: an {@link IntakeFile}'s stream checks itself against the size and SHA-256 taken when the
 * file was taken; a file that changed since throws {@link IntakeFile.Changed} at the end of the stream (or as soon as
 * it is longer than it was).
 */
class IntakeFileTest {

    static final byte[] TAKEN = "%PDF-1.4 the file as it was taken".getBytes(StandardCharsets.UTF_8);

    static String sha(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    /** The file as taken (name, size, SHA-256) whose content is now {@code now}. */
    static IntakeFile file(byte[] now) throws Exception {
        return new IntakeFile("a.pdf", TAKEN.length, sha(TAKEN), null, () -> new ByteArrayInputStream(now));
    }

    @Test
    void anUnchangedFileReadsToItsEnd() throws Exception {
        try (InputStream in = file(TAKEN).open()) {
            assertArrayEquals(TAKEN, in.readAllBytes());
            assertEquals(-1, in.read(), "the end can be read again");
        }
        try (InputStream in = file(TAKEN).open()) {
            assertEquals(TAKEN.length, in.skip(TAKEN.length + 10L), "skip reads (and checks) the bytes");
        }
    }

    @Test
    void sameSizeOtherContentIsChanged() throws Exception {
        byte[] other = TAKEN.clone();
        other[other.length - 1] ^= 1;
        try (InputStream in = file(other).open()) {
            assertThrows(IntakeFile.Changed.class, in::readAllBytes);
        }
    }

    @Test
    void shorterOrLongerIsChanged() throws Exception {
        byte[] shorter = java.util.Arrays.copyOf(TAKEN, TAKEN.length - 1);
        try (InputStream in = file(shorter).open()) {
            assertThrows(IntakeFile.Changed.class, in::readAllBytes);
        }
        byte[] longer = java.util.Arrays.copyOf(TAKEN, TAKEN.length + 1);
        try (InputStream in = file(longer).open()) {
            assertThrows(IntakeFile.Changed.class, () -> in.readNBytes(TAKEN.length + 1),
                    "longer than it was: refused before its end");
        }
        try (InputStream in = file(shorter).open()) {
            for (int i = 0; i < shorter.length; i++) assertTrue(in.read() >= 0);
            assertThrows(IntakeFile.Changed.class, in::read, "one byte at a time too");
        }
    }

    @Test
    void neverShowsTheNameOrContent() throws Exception {
        IntakeFile f = file(TAKEN);
        assertEquals("IntakeFile[" + TAKEN.length + " bytes]", f.toString());
        IOException e = new IntakeFile.Changed();
        assertFalse(e.getMessage().contains("a.pdf"));
    }
}
