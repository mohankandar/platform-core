package com.documentplatform.eraselog.file;

import com.documentplatform.eraselog.ChainKey;
import com.documentplatform.eraselog.ChainPoint;
import com.documentplatform.eraselog.EraseEntry;
import com.documentplatform.eraselog.EraseLogError;
import com.documentplatform.eraselog.EraseLogException;
import com.documentplatform.eraselog.EraseLogStore;
import com.documentplatform.eraselog.EraseRequest;
import com.documentplatform.eraselog.Seal;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The erase log as files in one directory, the configured location:
 * <ul>
 *   <li>{@value #LOG_FILE}: one entry per line, appended only, each line forced to disk ({@code fsync}) before
 *       {@link #append} returns;</li>
 *   <li>{@value #ID_FILE}: the log's ID, made once;</li>
 *   <li>{@value #CHECKPOINT_FILE}: the last pruned entry's number and hash, once something was pruned;</li>
 *   <li>{@value #LOCK_FILE}: locked while writing, so several app instances (processes) on the same location append
 *       one at a time.</li>
 * </ul>
 *
 * <p><b>Install rule:</b> the location must be on storage outside the database it protects — not in the database's
 * data directory, not on a volume included in the database's backups or snapshots — and backed up on its own, kept at
 * least as long as {@code EraseRetention#logRetention()}. Otherwise restoring the database would roll the log back
 * with it. Every app instance of one installation points at the same location (a shared volume).
 *
 * <p>A line cut short by a crash during a write (no line end) was never confirmed, so the erasure was not acted on:
 * it is ignored when reading and removed before the next write. Pruning writes the checkpoint first, then the shorter
 * log, each by an atomic rename; entries at or before the checkpoint are ignored, so a crash in between is harmless.
 */
public final class FileEraseLogStore implements EraseLogStore {

    public static final String LOG_FILE = "erase-log.jsonl";
    public static final String ID_FILE = "erase-log.id";
    public static final String CHECKPOINT_FILE = "erase-log.checkpoint";
    public static final String LOCK_FILE = "erase-log.lock";
    /** The log's seal (P044, SEC-3): from which entry on MACs are required, made with the log's key. */
    public static final String SEAL_FILE = "erase-log.seal";
    /** The highest entry a database applied with this log (P044, SEC-4): a restored database is below it. */
    public static final String APPLIED_FILE = "erase-log.applied";

    private static final ConcurrentHashMap<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();
    private static final Pattern SEQ = Pattern.compile("^\\{\"seq\":([0-9]{1,18}),");

    private final Path dir;
    private final Path logFile;
    private final UUID logId;

    /**
     * Opens the log at {@code directory}, creating the directory and the log's ID when they do not exist yet.
     *
     * @throws EraseLogException {@code ERASE_LOG_SETTINGS_INVALID} when it is not a directory,
     *                           {@code ERASE_LOG_UNREACHABLE} when it cannot be read or written
     */
    public FileEraseLogStore(Path directory) {
        this.dir = directory.toAbsolutePath().normalize();
        this.logFile = dir.resolve(LOG_FILE);
        if (Files.exists(dir) && !Files.isDirectory(dir)) {
            throw new EraseLogException(EraseLogError.ERASE_LOG_SETTINGS_INVALID, "the erase log location " + dir
                    + " is a file, not a directory");
        }
        try {
            boolean made = !Files.exists(dir);
            Files.createDirectories(dir);
            if (made) ownerOnly(dir);
        } catch (IOException e) {
            throw unreachable("created", e);
        }
        this.logId = locked(this::readOrCreateId);
    }

    /** SEC-49: a location made here is readable and writable by the service account only (where the system can say). */
    private static void ownerOnly(Path p) {
        try {
            Files.setPosixFilePermissions(p, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException | IOException e) {
            // Windows: the folder takes its parent's rights; the install guide says how to restrict them.
        }
    }

    @Override
    public EraseEntry append(EraseRequest request, Instant loggedAt) {
        return append(request, loggedAt, null);
    }

    @Override
    public Optional<Seal> seal() {
        Path f = dir.resolve(SEAL_FILE);
        try {
            if (!Files.exists(f, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
            return Optional.of(Lines.seal(Files.readString(f, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw unreachable("read", e);
        }
    }

    @Override
    public Seal sealAtHead(ChainKey key) {
        return locked(() -> {
            Optional<Seal> existing = seal();
            if (existing.isPresent()) return existing.get();
            Seal s = Seal.make(key, logId, head());
            Path tmp = dir.resolve(SEAL_FILE + ".new");
            try {
                fresh(tmp, Lines.seal(s, Instant.now()) + "\n");
                force(tmp);
                Files.move(tmp, dir.resolve(SEAL_FILE), StandardCopyOption.ATOMIC_MOVE);
                forceDirectory();
            } catch (IOException e) {
                deleteQuietly(tmp);
                throw unreachable("sealed", e);
            }
            return s;
        });
    }

    @Override
    public long appliedHighWater() {
        Path f = dir.resolve(APPLIED_FILE);
        try {
            if (!Files.exists(f, LinkOption.NOFOLLOW_LINKS)) return 0;
            return Lines.applied(logId, Files.readString(f, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw unreachable("read", e);
        }
    }

    @Override
    public void recordApplied(long seq) {
        locked(() -> {
            if (seq <= appliedHighWater()) return null;
            Path tmp = dir.resolve(APPLIED_FILE + ".new");
            try {
                fresh(tmp, Lines.applied(logId, seq) + "\n");
                force(tmp);
                Files.move(tmp, dir.resolve(APPLIED_FILE), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                deleteQuietly(tmp);
                throw unreachable("written", e);
            }
            return null;
        });
    }

    @Override
    public UUID logId() {
        return logId;
    }

    @Override
    public String location() {
        return dir.toString();
    }

    @Override
    public EraseEntry append(EraseRequest request, Instant loggedAt, ChainKey key) {
        return locked(() -> {
            try (FileChannel ch = FileChannel.open(logFile, StandardOpenOption.CREATE, StandardOpenOption.READ,
                    StandardOpenOption.WRITE)) {
                long valid = validLength(ch);
                if (valid < ch.size()) {
                    ch.truncate(valid); // a line cut short by a crash: never confirmed, so never acted on
                    ch.force(true);
                }
                ChainPoint checkpoint = checkpoint();
                ChainPoint head = checkpoint;
                List<String> last = linesFromEnd(ch, valid, 0, true);
                if (!last.isEmpty()) {
                    EraseEntry e = Lines.entry(logId, last.get(last.size() - 1), 0);
                    if (e.seq() > checkpoint.seq()) {
                        head = e.point();
                    }
                }
                EraseEntry entry = EraseEntry.link(logId, head, loggedAt, request, key);
                ByteBuffer bytes = ByteBuffer.wrap((Lines.entry(entry) + "\n").getBytes(StandardCharsets.UTF_8));
                ch.position(valid);
                while (bytes.hasRemaining()) {
                    ch.write(bytes);
                }
                ch.force(true);
                if (valid == 0) {
                    forceDirectory();
                }
                return entry;
            } catch (IOException e) {
                throw unreachable("written", e);
            }
        });
    }

    @Override
    public ChainPoint checkpoint() {
        Path file = dir.resolve(CHECKPOINT_FILE);
        try {
            return Lines.checkpoint(logId, Files.readString(file, StandardCharsets.UTF_8));
        } catch (NoSuchFileException e) {
            return ChainPoint.START;
        } catch (IOException e) {
            throw unreachable("read", e);
        }
    }

    @Override
    public ChainPoint head() {
        return locked(() -> {
            ChainPoint checkpoint = checkpoint();
            try (FileChannel ch = FileChannel.open(logFile, StandardOpenOption.READ)) {
                List<String> last = linesFromEnd(ch, validLength(ch), 0, true);
                if (last.isEmpty()) {
                    return checkpoint;
                }
                EraseEntry e = Lines.entry(logId, last.get(last.size() - 1), 0);
                return e.seq() > checkpoint.seq() ? e.point() : checkpoint;
            } catch (NoSuchFileException e) {
                return checkpoint;
            } catch (IOException e) {
                throw unreachable("read", e);
            }
        });
    }

    @Override
    public List<EraseEntry> after(long seq) {
        return locked(() -> {
            long from = Math.max(seq, checkpoint().seq());
            try (FileChannel ch = FileChannel.open(logFile, StandardOpenOption.READ)) {
                List<EraseEntry> out = new ArrayList<>();
                for (String line : linesFromEnd(ch, validLength(ch), from, false)) {
                    EraseEntry e = Lines.entry(logId, line, 0);
                    if (e.seq() > from) {
                        out.add(e);
                    }
                }
                return out;
            } catch (NoSuchFileException e) {
                return List.of();
            } catch (IOException e) {
                throw unreachable("read", e);
            }
        });
    }

    @Override
    public void scan(Consumer<EraseEntry> each) {
        // SEC-49: under the lock, so another instance's prune cannot swap the file while it is read (Windows, SMB).
        locked(() -> {
            scanUnlocked(each);
            return null;
        });
    }

    private void scanUnlocked(Consumer<EraseEntry> each) {
        long after = checkpoint().seq();
        try (FileChannel ch = FileChannel.open(logFile, StandardOpenOption.READ)) {
            long valid = validLength(ch);
            try (BufferedReader r = new BufferedReader(new InputStreamReader(limited(Channels.newInputStream(
                    ch.position(0)), valid), StandardCharsets.UTF_8))) {
                String line;
                long n = 0;
                while ((line = r.readLine()) != null) {
                    n++;
                    EraseEntry e = Lines.entry(logId, line, n);
                    if (e.seq() > after) {
                        each.accept(e);
                    }
                }
            }
        } catch (NoSuchFileException e) {
            // no entry written yet
        } catch (IOException e) {
            throw unreachable("read", e);
        }
    }

    @Override
    public void pruneThrough(long seq) {
        locked(() -> {
            ChainPoint checkpoint = checkpoint();
            if (seq <= checkpoint.seq()) {
                return null;
            }
            Path kept = dir.resolve(LOG_FILE + ".pruning");
            ChainPoint[] at = {null};
            try {
                Files.deleteIfExists(kept); // a leftover (or a link in its place) goes; never what a link points to
            } catch (IOException e) {
                throw unreachable("pruned", e);
            }
            try (BufferedWriter w = Files.newBufferedWriter(kept, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                scan(e -> {
                    if (e.seq() == seq) {
                        at[0] = e.point();
                    }
                    if (e.seq() > seq) {
                        try {
                            w.write(Lines.entry(e));
                            w.write('\n');
                        } catch (IOException x) {
                            throw unreachable("written", x);
                        }
                    }
                });
            } catch (IOException e) {
                throw unreachable("written", e);
            }
            if (at[0] == null) {
                deleteQuietly(kept);
                throw new EraseLogException(EraseLogError.ERASE_LOG_FORMAT, "entry " + seq + " is not in the log at "
                        + dir + "; nothing pruned");
            }
            try {
                force(kept);
                Path cp = dir.resolve(CHECKPOINT_FILE + ".new");
                fresh(cp, Lines.checkpoint(logId, at[0], Instant.now()) + "\n");
                force(cp);
                Files.move(cp, dir.resolve(CHECKPOINT_FILE), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                Files.move(kept, logFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                forceDirectory();
            } catch (IOException e) {
                deleteQuietly(kept);
                throw unreachable("pruned", e);
            }
            return null;
        });
    }

    @Override
    public String toString() {
        return "FileEraseLogStore[" + dir + ", log " + logId + "]";
    }

    // ---- inside ----

    private UUID readOrCreateId() {
        Path file = dir.resolve(ID_FILE);
        try {
            if (Files.exists(file)) {
                try {
                    return UUID.fromString(Files.readString(file, StandardCharsets.UTF_8).strip());
                } catch (IllegalArgumentException e) {
                    throw new EraseLogException(EraseLogError.ERASE_LOG_FORMAT, "the log ID file " + file
                            + " is not readable");
                }
            }
            if (Files.exists(logFile)) {
                throw new EraseLogException(EraseLogError.ERASE_LOG_FORMAT, "the erase log at " + dir + " has entries "
                        + "but no " + ID_FILE + ": restore that file from the log's backup");
            }
            UUID id = UUID.randomUUID();
            Path tmp = dir.resolve(ID_FILE + ".new");
            fresh(tmp, id + "\n");
            force(tmp);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            forceDirectory();
            return id;
        } catch (IOException e) {
            throw unreachable("created", e);
        }
    }

    /** The log's own files, temporary ones included: never a link, so nothing outside the location is written. */
    private static final List<String> OWN_FILES = List.of(LOG_FILE, LOG_FILE + ".pruning", CHECKPOINT_FILE,
            CHECKPOINT_FILE + ".new", ID_FILE, ID_FILE + ".new", LOCK_FILE, SEAL_FILE, SEAL_FILE + ".new", APPLIED_FILE,
            APPLIED_FILE + ".new");

    /**
     * Refuses when one of the log's own files is a symbolic link, a Windows junction or another reparse point: the log
     * never writes, moves or reads through one ({@code ERASE_LOG_LINK_REFUSED}). Called before every locked operation
     * and every scan.
     */
    private void noLinks() {
        for (String name : OWN_FILES) {
            Path p = dir.resolve(name);
            BasicFileAttributes a;
            try {
                a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (IOException absent) {
                continue;
            }
            if (a.isSymbolicLink() || a.isOther()) {
                throw new EraseLogException(EraseLogError.ERASE_LOG_LINK_REFUSED, "the erase log's file " + name
                        + " at " + dir + " is a link (symbolic link or junction); nothing was written. Remove it and "
                        + "allow only the app's service account to write the erase log location");
            }
        }
    }

    /** A temporary file of the log, made new: an old one (or a link left in its place) goes first. */
    private static void fresh(Path tmp, String text) throws IOException {
        Files.deleteIfExists(tmp); // removes a link itself, never what it points to
        Files.writeString(tmp, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    /** Runs {@code work} holding this location's lock: in this JVM, and across processes by a file lock. */
    private <T> T locked(Supplier<T> work) {
        ReentrantLock jvm = LOCKS.computeIfAbsent(dir, d -> new ReentrantLock());
        if (jvm.isHeldByCurrentThread()) {
            return work.get(); // already inside a locked operation (a scan within a prune): both locks are held
        }
        jvm.lock();
        try {
            noLinks();
            try (FileChannel lockFile = FileChannel.open(dir.resolve(LOCK_FILE), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE); FileLock ignored = lockFile.lock()) {
                return work.get();
            } catch (IOException e) {
                throw unreachable("locked", e);
            }
        } finally {
            jvm.unlock();
        }
    }

    /** The length up to and including the last line end: a line after it was cut short. */
    private static long validLength(FileChannel ch) throws IOException {
        long size = ch.size();
        long pos = size;
        ByteBuffer b = ByteBuffer.allocate(4096);
        while (pos > 0) {
            int n = (int) Math.min(b.capacity(), pos);
            b.clear().limit(n);
            ch.read(b, pos - n);
            for (int i = n - 1; i >= 0; i--) {
                if (b.get(i) == '\n') {
                    return pos - n + i + 1;
                }
            }
            pos -= n;
        }
        return 0;
    }

    /**
     * The complete lines before {@code end}, in file order, read from the end back: only the last one when
     * {@code lastOnly}, else back to (and including) the first line whose number is at most {@code stopAtSeq}.
     */
    private static List<String> linesFromEnd(FileChannel ch, long end, long stopAtSeq, boolean lastOnly)
            throws IOException {
        long window = 8192;
        while (true) {
            long start = Math.max(0, end - window);
            ByteBuffer b = ByteBuffer.allocate((int) (end - start));
            ch.read(b, start);
            String text = new String(b.array(), 0, b.position(), StandardCharsets.UTF_8);
            if (start > 0) {
                int nl = text.indexOf('\n');
                text = nl < 0 ? "" : text.substring(nl + 1);
            }
            List<String> lines = new ArrayList<>();
            for (String line : text.split("\n", -1)) {
                if (!line.isEmpty()) {
                    lines.add(line);
                }
            }
            if (lastOnly && !lines.isEmpty()) {
                return lines.subList(lines.size() - 1, lines.size());
            }
            if (!lastOnly) {
                for (int i = lines.size() - 1; i >= 0; i--) {
                    Matcher m = SEQ.matcher(lines.get(i));
                    if (m.find() && Long.parseLong(m.group(1)) <= stopAtSeq) {
                        return lines.subList(i, lines.size());
                    }
                }
            }
            if (start == 0) {
                return lines;
            }
            window *= 4;
        }
    }

    private static InputStream limited(InputStream in, long limit) {
        return new InputStream() {
            long left = limit;

            @Override
            public int read() throws IOException {
                if (left <= 0) {
                    return -1;
                }
                int c = in.read();
                if (c >= 0) {
                    left--;
                }
                return c;
            }

            @Override
            public int read(byte[] buf, int off, int len) throws IOException {
                if (left <= 0) {
                    return -1;
                }
                int n = in.read(buf, off, (int) Math.min(len, left));
                if (n > 0) {
                    left -= n;
                }
                return n;
            }
        };
    }

    private static void force(Path file) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
            ch.force(true);
        }
    }

    /** Makes a new or renamed file's directory entry durable; not possible on every system (Windows), then skipped. */
    private void forceDirectory() {
        try (FileChannel ch = FileChannel.open(dir, StandardOpenOption.READ)) {
            ch.force(true);
        } catch (IOException | UnsupportedOperationException e) {
            // Windows cannot open a directory as a channel; NTFS journals the rename.
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // a leftover temporary file is overwritten next time
        }
    }

    private EraseLogException unreachable(String what, IOException e) {
        return new EraseLogException(EraseLogError.ERASE_LOG_UNREACHABLE, "the erase log at " + dir + " could not be "
                + what + " (" + e.getClass().getSimpleName() + ")");
    }
}
