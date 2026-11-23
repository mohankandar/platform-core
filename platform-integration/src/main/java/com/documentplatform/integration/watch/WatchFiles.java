package com.documentplatform.integration.watch;

import com.documentplatform.integration.folder.ExportFiles;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * The file operations a watched folder uses, so a test can put a mock folder ({@code MockFolder} in the testkit) in
 * place of the real one. Built: {@code folder.LocalFiles}. Exceptions are the JDK's; their texts are never logged or
 * passed on.
 */
public interface WatchFiles extends ExportFiles {

    /**
     * One entry of a folder, read without following links.
     *
     * @param link a symbolic link, a Windows junction or any other special entry: never followed, never read
     */
    record Entry(String name, boolean directory, boolean link, long size, Instant modified) {
    }

    /** The entries directly in a folder, without following links. */
    List<Entry> list(Path folder) throws IOException;

    /** Moves a file or folder in one step (an atomic rename on the same volume); never replaces anything. */
    void move(Path from, Path to) throws IOException;

    /** The real path (links resolved), to check that a file stays inside the watched folder. */
    Path realPath(Path p) throws IOException;
}
