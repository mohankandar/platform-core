package com.documentplatform.monitoring.checks;

import com.documentplatform.monitoring.DependencyCheck;
import com.documentplatform.monitoring.MonitoringCode;
import com.documentplatform.monitoring.Verdict;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * A configured folder: it exists, the app may read it and write to it (a probe file is written and removed), and its
 * disk has room: HAVING_PROBLEMS from {@code warnAt} percent used (default 90), DOWN from {@code downAt} (default 95).
 */
public final class FileLocationCheck implements DependencyCheck {

    /** How the folder is seen: the real file system by default (replaceable in tests). */
    public interface Disk {
        boolean isDirectory(Path p);

        boolean isReadable(Path p);

        /** Writes and removes a probe file; false when that is refused. */
        boolean canWrite(Path p);

        /** Percent of the folder's disk in use; empty when unknown. */
        OptionalLong usedPercent(Path p) throws IOException;

        Disk REAL = new Disk() {
            @Override
            public boolean isDirectory(Path p) {
                return Files.isDirectory(p);
            }

            @Override
            public boolean isReadable(Path p) {
                return Files.isReadable(p);
            }

            @Override
            public boolean canWrite(Path p) {
                try {
                    Files.delete(Files.createTempFile(p, ".platform-check-", ".tmp"));
                    return true;
                } catch (IOException | SecurityException e) {
                    return false;
                }
            }

            @Override
            public OptionalLong usedPercent(Path p) throws IOException {
                FileStore store = Files.getFileStore(p);
                long total = store.getTotalSpace();
                if (total <= 0) return OptionalLong.empty();
                return OptionalLong.of(Math.round(100.0 * (total - store.getUsableSpace()) / total));
            }
        };
    }

    private final String location;
    private final Path path;
    private final boolean writable;
    private final int warnAt;
    private final int downAt;
    private final boolean required;
    private final Disk disk;

    /**
     * @param location what the folder is for, for example {@code erase-log} or {@code intake-work-folder}
     * @param writable whether the app writes to it (else readable is enough)
     */
    public FileLocationCheck(String location, Path path, boolean writable, int warnAt, int downAt, boolean required,
                             Disk disk) {
        this.location = Objects.requireNonNull(location, "location");
        this.path = Objects.requireNonNull(path, "path");
        if (warnAt < 1 || downAt > 100 || warnAt > downAt) {
            throw new IllegalArgumentException("thresholds must be 1 <= warnAt <= downAt <= 100");
        }
        this.writable = writable;
        this.warnAt = warnAt;
        this.downAt = downAt;
        this.required = required;
        this.disk = Objects.requireNonNull(disk, "disk");
    }

    public FileLocationCheck(String location, Path path, boolean writable, int warnAt, int downAt, boolean required) {
        this(location, path, writable, warnAt, downAt, required, Disk.REAL);
    }

    public FileLocationCheck(String location, Path path) {
        this(location, path, true, 90, 95, false);
    }

    @Override
    public String name() {
        return "folder-" + location;
    }

    @Override
    public boolean requiredAtStartup() {
        return required;
    }

    @Override
    public Verdict check() {
        Map<String, String> where = Map.of("path", path.toString(), "location", location);
        if (!disk.isDirectory(path)) return Verdict.down(MonitoringCode.MON_PATH_MISSING, where);
        if (!disk.isReadable(path)) return Verdict.down(MonitoringCode.MON_PATH_NOT_READABLE, where);
        if (writable && !disk.canWrite(path)) return Verdict.down(MonitoringCode.MON_PATH_NOT_WRITABLE, where);
        try {
            OptionalLong used = disk.usedPercent(path);
            if (used.isPresent()) {
                Map<String, String> d = Map.of("path", path.toString(), "location", location,
                        "used", String.valueOf(used.getAsLong()), "warnAt", String.valueOf(warnAt),
                        "downAt", String.valueOf(downAt));
                if (used.getAsLong() >= downAt) return Verdict.down(MonitoringCode.MON_DISK_FULL, d);
                if (used.getAsLong() >= warnAt) return Verdict.problems(MonitoringCode.MON_DISK_ALMOST_FULL, d);
            }
        } catch (IOException e) {
            return Verdict.down(MonitoringCode.MON_PATH_NOT_READABLE, where);
        }
        return Verdict.working();
    }
}
