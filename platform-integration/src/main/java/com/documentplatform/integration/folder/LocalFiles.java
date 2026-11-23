package com.documentplatform.integration.folder;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * Local disks and network shares through the JDK. On Windows a share path ({@code \\server\share\...}) is used as it
 * is, under the service account's own rights; on other systems a share must be mounted and its mount path configured.
 * Files are forced to the disk when closed; symbolic links are not followed when deleting or listing. Used by folder
 * export and the watched folder.
 */
public final class LocalFiles implements com.documentplatform.integration.watch.WatchFiles {

    public static final LocalFiles INSTANCE = new LocalFiles();

    private LocalFiles() {
    }

    @Override
    public Path resolve(String folder) throws IOException {
        try {
            Path p = Path.of(folder);
            if (!p.isAbsolute()) throw new NoSuchFileException("not an absolute path on this system");
            return p;
        } catch (InvalidPathException e) {
            throw new NoSuchFileException("not a path on this system");
        }
    }

    @Override
    public boolean reachable(Path target) {
        Path root = target.getRoot();
        try {
            return root == null || Files.isDirectory(root);
        } catch (SecurityException e) {
            return false;
        }
    }

    @Override
    public boolean isDirectory(Path p) {
        try {
            return Files.isDirectory(p);
        } catch (SecurityException e) {
            return false;
        }
    }

    @Override
    public boolean exists(Path p) {
        try {
            return Files.exists(p, LinkOption.NOFOLLOW_LINKS);
        } catch (SecurityException e) {
            return false;
        }
    }

    @Override
    public void createDirectory(Path p) throws IOException {
        Files.createDirectory(p);
    }

    @Override
    public OutputStream create(Path p) throws IOException {
        FileChannel ch = FileChannel.open(p, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return new FilterOutputStream(Channels.newOutputStream(ch)) {
            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                out.write(b, off, len);
            }

            @Override
            public void close() throws IOException {
                try (ch) {
                    out.flush();
                    ch.force(true);
                }
            }
        };
    }

    @Override
    public InputStream open(Path p) throws IOException {
        return Files.newInputStream(p);
    }

    @Override
    public void rename(Path from, Path to) throws IOException {
        Files.move(from, to);
    }

    @Override
    public void delete(Path p) throws IOException {
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return;
        if (!Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(p);
            return;
        }
        Files.walkFileTree(p, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                if (e != null) throw e;
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    @Override
    public long usableSpace(Path p) throws IOException {
        return Files.getFileStore(p).getUsableSpace();
    }

    @Override
    public List<Entry> list(Path folder) throws IOException {
        List<Entry> out = new ArrayList<>();
        try (DirectoryStream<Path> s = Files.newDirectoryStream(folder)) {
            for (Path p : s) {
                BasicFileAttributes a;
                try {
                    a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                } catch (NoSuchFileException gone) {
                    continue; // moved away meanwhile
                }
                // A Windows junction is a directory that is also "other" (a reparse point): treated as a link.
                boolean link = a.isSymbolicLink() || a.isOther();
                out.add(new Entry(p.getFileName().toString(), a.isDirectory() && !link, link, a.size(),
                        a.lastModifiedTime().toInstant()));
            }
        }
        return out;
    }

    @Override
    public void move(Path from, Path to) throws IOException {
        if (Files.exists(to, LinkOption.NOFOLLOW_LINKS)) throw new FileAlreadyExistsException(to.getFileName().toString());
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
    }

    @Override
    public Path realPath(Path p) throws IOException {
        return p.toRealPath();
    }
}
