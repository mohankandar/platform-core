package com.documentplatform.integration.testkit;

import com.documentplatform.integration.config.FolderTarget;
import com.documentplatform.integration.folder.ExportFiles;
import com.documentplatform.integration.folder.LocalFiles;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * A mock export folder for folder export tests: a real folder (so a test can look at what was written) with
 * switches for the failures a share has: missing, read-only, full (writing fails after N bytes), slow, unreachable,
 * a disk that changes what it stores (for the checksum check), and "an existing package folder". Give
 * {@link #folder()} as the configuration's folder and this object as the exporter's {@link ExportFiles}. For tests
 * only.
 */
public final class MockFolder implements ExportFiles {

    private final Path folder;
    private volatile boolean missing;
    private volatile boolean readOnly;
    private volatile boolean unreachable;
    private volatile boolean corrupt;
    private volatile long fullAfter = -1;
    private volatile Duration slow = Duration.ZERO;
    private volatile long reportedSpace = -1;
    private final AtomicLong written = new AtomicLong();

    private MockFolder(Path folder) {
        this.folder = folder;
    }

    /** A new, empty export folder {@code exports} under {@code parent} (a JUnit temporary folder). */
    public static MockFolder in(Path parent) {
        try {
            return new MockFolder(Files.createDirectories(parent.resolve("exports")).toAbsolutePath());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The folder to configure ({@link FolderTarget#folder()}). */
    public String folder() {
        return folder.toString();
    }

    public Path path() {
        return folder;
    }

    /** Everything back to working. */
    public MockFolder working() {
        missing = false;
        readOnly = false;
        unreachable = false;
        corrupt = false;
        fullAfter = -1;
        slow = Duration.ZERO;
        reportedSpace = -1;
        return this;
    }

    /** The target folder does not exist (it is reported missing; what was written stays). */
    public MockFolder missing(boolean on) {
        missing = on;
        return this;
    }

    /** No right to create, write, rename or delete anything (AccessDeniedException). */
    public MockFolder readOnly(boolean on) {
        readOnly = on;
        return this;
    }

    /** The share's server does not answer: every operation fails, and it is not reachable. */
    public MockFolder unreachable(boolean on) {
        unreachable = on;
        return this;
    }

    /** The disk or quota is full after {@code bytes} more bytes: writing fails with "No space left on device". */
    public MockFolder fullAfter(long bytes) {
        written.set(0);
        fullAfter = bytes;
        return this;
    }

    /**
     * The free space the folder reports (before anything is written), in bytes; -1 for the real one. Writing itself
     * is not limited by this (see {@link #fullAfter}).
     */
    public MockFolder reportedSpace(long bytes) {
        reportedSpace = bytes;
        return this;
    }

    /** Every write of a chunk takes this long. */
    public MockFolder slow(Duration perWrite) {
        slow = perWrite;
        return this;
    }

    /** Reading a file back gives other bytes than were written (the first byte changed). */
    public MockFolder corrupt(boolean on) {
        corrupt = on;
        return this;
    }

    /** A finished package folder {@code name} with this manifest text (for "already received" and "different"). */
    public MockFolder existingPackage(String name, String manifest) {
        try {
            Path p = Files.createDirectories(folder.resolve(name));
            Files.writeString(p.resolve(FolderTarget.MANIFEST_FILE), manifest, StandardCharsets.UTF_8);
            return this;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The names directly in the export folder (package folders, {@code .partial} ones included), sorted. */
    public List<String> entries() {
        return list(folder);
    }

    /** The names in one package folder, sorted. */
    public List<String> entries(String packageFolder) {
        return list(folder.resolve(packageFolder));
    }

    public byte[] read(String packageFolder, String file) {
        try {
            return Files.readAllBytes(folder.resolve(packageFolder).resolve(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String manifest(String packageFolder) {
        return new String(read(packageFolder, FolderTarget.MANIFEST_FILE), StandardCharsets.UTF_8);
    }

    private static List<String> list(Path p) {
        if (!Files.isDirectory(p)) return List.of();
        try (Stream<Path> s = Files.list(p)) {
            return s.map(x -> x.getFileName().toString()).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- ExportFiles ----

    private void gate() throws IOException {
        if (unreachable) throw new IOException("The network path was not found");
    }

    private void writable(Path p) throws IOException {
        gate();
        if (readOnly) throw new AccessDeniedException(p.getFileName().toString());
    }

    @Override
    public Path resolve(String f) throws IOException {
        return LocalFiles.INSTANCE.resolve(f);
    }

    @Override
    public boolean reachable(Path target) {
        return !unreachable;
    }

    @Override
    public boolean isDirectory(Path p) {
        if (unreachable || missing && p.equals(folder)) return false;
        return LocalFiles.INSTANCE.isDirectory(p);
    }

    @Override
    public boolean exists(Path p) {
        if (unreachable || missing && p.startsWith(folder)) return false;
        return LocalFiles.INSTANCE.exists(p);
    }

    @Override
    public void createDirectory(Path p) throws IOException {
        writable(p);
        LocalFiles.INSTANCE.createDirectory(p);
    }

    @Override
    public OutputStream create(Path p) throws IOException {
        writable(p);
        OutputStream real = LocalFiles.INSTANCE.create(p);
        return new FilterOutputStream(real) {
            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                gate();
                Duration d = slow;
                if (!d.isZero()) {
                    try {
                        Thread.sleep(d.toMillis());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrupted");
                    }
                }
                long limit = fullAfter;
                if (limit >= 0 && written.addAndGet(len) > limit) {
                    throw new IOException("No space left on device");
                }
                out.write(b, off, len);
            }

            @Override
            public void write(int b) throws IOException {
                write(new byte[]{(byte) b}, 0, 1);
            }
        };
    }

    @Override
    public InputStream open(Path p) throws IOException {
        gate();
        InputStream real = LocalFiles.INSTANCE.open(p);
        if (!corrupt) return real;
        return new FilterInputStream(real) {
            private boolean first = true;

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                int n = in.read(b, off, len);
                if (n > 0 && first) {
                    b[off] = (byte) (b[off] ^ 0x01);
                    first = false;
                }
                return n;
            }
        };
    }

    @Override
    public void rename(Path from, Path to) throws IOException {
        writable(to);
        LocalFiles.INSTANCE.rename(from, to);
    }

    @Override
    public void delete(Path p) throws IOException {
        writable(p);
        LocalFiles.INSTANCE.delete(p);
    }

    @Override
    public long usableSpace(Path p) throws IOException {
        gate();
        long reported = reportedSpace;
        return reported >= 0 ? reported : LocalFiles.INSTANCE.usableSpace(p);
    }

    @Override
    public String toString() {
        return "MockFolder";
    }
}
