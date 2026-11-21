package com.documentplatform.setup;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/**
 * The install's config fragment that setup writes: {@code platform.licensing.tenant-id} for this customer. The app
 * imports it (for example {@code spring.config.import=optional:file:./platform-tenant.properties}). Setup creates it,
 * leaves it alone when it already names this customer, and refuses when it names another one.
 */
final class ConfigFragment {

    static final String DEFAULT_NAME = "platform-tenant.properties";
    static final String KEY = "platform.licensing.tenant-id";

    enum State { MISSING, SAME, NO_KEY }

    private ConfigFragment() {
    }

    /** @throws SetupException SETUP_CONFIG_OTHER_TENANT or SETUP_FILE_UNREADABLE */
    static State check(Path file, String tenantId) {
        if (!Files.exists(file)) return State.MISSING;
        Properties p = new Properties();
        try {
            p.load(new StringReader(Files.readString(file, StandardCharsets.UTF_8)));
        } catch (IOException | IllegalArgumentException e) {
            throw new SetupException(SetupRefusal.SETUP_FILE_UNREADABLE, "--config-out " + file.getFileName()
                    + " cannot be read (" + e.getClass().getSimpleName() + ").");
        }
        String value = p.getProperty(KEY);
        if (value == null) return State.NO_KEY;
        if (value.strip().equals(tenantId)) return State.SAME;
        throw new SetupException(SetupRefusal.SETUP_CONFIG_OTHER_TENANT, "--config-out " + file.getFileName()
                + " sets " + KEY + " to another customer than " + tenantId + ".");
    }

    /** Writes the fragment (or adds the key to an existing one), replacing the file in one step. */
    static void write(Path file, String tenantId, State state) throws IOException {
        String line = KEY + "=" + tenantId + System.lineSeparator();
        String text = state == State.NO_KEY
                ? withNewline(Files.readString(file, StandardCharsets.UTF_8)) + line
                : "# Written by the platform setup command: this installation's customer (tenant) ID, from the licence."
                + System.lineSeparator() + line;
        Path dir = file.toAbsolutePath().getParent();
        Path tmp = Files.createTempFile(dir, ".platform-setup", ".tmp");
        try {
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static String withNewline(String s) {
        return s.isEmpty() || s.endsWith("\n") ? s : s + System.lineSeparator();
    }
}
