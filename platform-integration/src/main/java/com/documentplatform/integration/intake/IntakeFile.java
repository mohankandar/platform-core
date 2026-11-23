package com.documentplatform.integration.intake;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * One incoming file as the app gets it: its name, size and SHA-256 (computed by platform-core when it took the file)
 * and its content as a stream. The stream checks itself: at its end, a size or checksum that differs from the one
 * given here throws {@link Changed} (the file changed after it was taken). Never logged; {@link #toString()} holds the
 * size only.
 */
public final class IntakeFile {

    /** The content changed after it was taken: its size or SHA-256 differs at the end of the stream. */
    public static final class Changed extends IOException {
        public Changed() {
            super("The file changed after it was taken", null);
        }
    }

    /** Opens the content. */
    @FunctionalInterface
    public interface Content {
        InputStream open() throws IOException;
    }

    private final String name;
    private final long size;
    private final String sha256;
    private final String contentType;
    private final Content content;

    /**
     * @param contentType the content type the source gave, or null
     */
    public IntakeFile(String name, long size, String sha256, String contentType, Content content) {
        this.name = Objects.requireNonNull(name, "name");
        this.size = size;
        this.sha256 = Objects.requireNonNull(sha256, "sha256");
        this.contentType = contentType;
        this.content = Objects.requireNonNull(content, "content");
    }

    public String name() {
        return name;
    }

    public long size() {
        return size;
    }

    /** Lower-case hex. */
    public String sha256() {
        return sha256;
    }

    /** The content type the source gave, or null. */
    public String contentType() {
        return contentType;
    }

    /** The content; checked against the size and SHA-256 at its end ({@link Changed}). Only during the handler. */
    public InputStream open() throws IOException {
        return new Checked(content.open(), size, sha256);
    }

    @Override
    public String toString() {
        return "IntakeFile[" + size + " bytes]";
    }

    private static final class Checked extends FilterInputStream {
        private final long size;
        private final String sha256;
        private final MessageDigest digest;
        private long read;
        private boolean checked;

        Checked(InputStream in, long size, String sha256) {
            super(in);
            this.size = size;
            this.sha256 = sha256;
            try {
                this.digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            if (n > 0) {
                digest.update(b, off, n);
                read += n;
                if (read > size) throw new Changed();
            } else if (n < 0 && !checked) {
                checked = true;
                if (read != size || !HexFormat.of().formatHex(digest.digest()).equals(sha256)) throw new Changed();
            }
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            byte[] buf = new byte[8192];
            long left = n;
            while (left > 0) {
                int r = read(buf, 0, (int) Math.min(buf.length, left));
                if (r < 0) break;
                left -= r;
            }
            return n - left;
        }

        @Override
        public boolean markSupported() {
            return false;
        }
    }
}
