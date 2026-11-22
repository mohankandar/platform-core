package com.documentplatform.integration.folder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;

/**
 * The file operations a folder export uses, so a test can put a mock folder ({@code MockFolder} in the testkit) in
 * place of the real one. Built: {@link LocalFiles} (local disks and network shares through the JDK). Exceptions are
 * the JDK's ({@link java.nio.file.AccessDeniedException}, {@link java.nio.file.NoSuchFileException}, ...); their
 * texts are never logged or passed on.
 */
public interface ExportFiles {

    /** The configured folder as a path on this system. @throws IOException when it is not an absolute path here */
    Path resolve(String folder) throws IOException;

    /** Whether the volume or network share the target is on answers (a share's server and share name). */
    boolean reachable(Path target);

    boolean isDirectory(Path p);

    boolean exists(Path p);

    void createDirectory(Path p) throws IOException;

    /** A new file (never an existing one); {@code close()} writes it through to the disk before it returns. */
    OutputStream create(Path p) throws IOException;

    InputStream open(Path p) throws IOException;

    /** Renames within the same folder; never replaces an existing file or folder. */
    void rename(Path from, Path to) throws IOException;

    /** Deletes a file, or a folder with everything in it. Nothing when it does not exist. */
    void delete(Path p) throws IOException;

    /** Bytes this account may still write there (the disk's free space within its quota). */
    long usableSpace(Path p) throws IOException;
}
