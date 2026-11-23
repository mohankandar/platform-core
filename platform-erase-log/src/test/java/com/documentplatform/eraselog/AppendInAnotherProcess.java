package com.documentplatform.eraselog;

import com.documentplatform.eraselog.file.FileEraseLogStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

/**
 * Started by {@link FileEraseLogStoreTest} as a second JVM (another "app instance"): waits for the go file, then
 * appends {@code count} entries named {@code <prefix>-<i>} to the log at {@code location}. Args: location, prefix,
 * count, go file.
 */
public final class AppendInAnotherProcess {

    private AppendInAnotherProcess() {
    }

    public static void main(String[] args) throws Exception {
        Path location = Path.of(args[0]);
        String prefix = args[1];
        int count = Integer.parseInt(args[2]);
        Path go = Path.of(args[3]);
        FileEraseLogStore store = new FileEraseLogStore(location);
        long until = System.nanoTime() + 60_000_000_000L;
        while (!Files.exists(go)) {
            if (System.nanoTime() > until) {
                System.exit(2);
            }
            Thread.onSpinWait();
        }
        for (int i = 0; i < count; i++) {
            store.append(new EraseRequest(UUID.fromString("00000000-0000-0000-0000-0000000000a1"), "PRODUCT_A",
                    "business_record", prefix + "-" + i, "case-fields", Instant.parse("2026-09-01T10:00:00Z"),
                    ErasedBy.rule("RET-7Y"), "AUD-" + prefix + "-" + i), Instant.now());
        }
        System.exit(0);
    }
}
