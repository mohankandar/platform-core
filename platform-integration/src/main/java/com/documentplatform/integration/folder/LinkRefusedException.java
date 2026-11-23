package com.documentplatform.integration.folder;

import java.io.IOException;

/**
 * A symbolic link, a Windows junction or another reparse point stands where the platform expects a real file or
 * folder that it is about to delete, move, rename or walk. Nothing behind the link is touched. Thrown by
 * {@link LocalFiles} (and by any {@link ExportFiles} that refuses links the same way); folder export answers
 * {@code INT_FOLDER_LINK_REFUSED}, the watched folder {@code INT_WATCH_LINK_REFUSED}. The text names the entry's
 * file name only.
 */
public final class LinkRefusedException extends IOException {

    public LinkRefusedException(String fileName) {
        super("a link stands where a real file or folder is expected: " + fileName);
    }
}
