package com.documentplatform.integration.watch;

import com.documentplatform.integration.config.WatchSource;
import com.documentplatform.integration.intake.IntakeFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One drop of a watched folder, as the app's handler gets it: claimed, its files checked (SHA-256, size) and scanned.
 * Its meaning (levels, identifiers, document types, what a missing or extra file means) is the app's.
 *
 * @param integrationId the watched-folder integration
 * @param dropId        the drop's name: its subfolder, or its files' base name (the app's idempotency key)
 * @param claimId       this claim's fencing token (unique per claim)
 * @param files         the drop's files, the trigger not among them (valid only while the handler runs)
 * @param trigger       the trigger file
 * @param values        the values the configuration's fixed mapping extracted (name → every match), empty without a
 *                      mapping
 * @param unlisted      with a file list in the mapping: files of the drop the list does not name (else empty)
 * @param missing       with a file list in the mapping: names in the list with no file (else empty)
 */
public record Drop(UUID integrationId, String dropId, String claimId, List<IntakeFile> files, Trigger trigger,
                   Map<String, List<String>> values, List<String> unlisted, List<String> missing) {

    /**
     * The trigger file: its name, raw bytes and type. A JSON or XML trigger was read with the hardened parser; the app
     * may use the extracted {@link Drop#values()} or read the raw bytes itself.
     */
    public record Trigger(String name, byte[] raw, WatchSource.TriggerType type) {
        public Trigger {
            Objects.requireNonNull(name, "name");
            raw = raw.clone();
            Objects.requireNonNull(type, "type");
        }

        @Override
        public byte[] raw() {
            return raw.clone();
        }

        /** The raw bytes as UTF-8 text. */
        public String text() {
            return new String(raw, StandardCharsets.UTF_8);
        }

        /** Whether it is an empty marker ({@code .lck}, {@code .done}). */
        public boolean empty() {
            return raw.length == 0;
        }

        @Override
        public String toString() {
            return "Trigger[" + type + ", " + raw.length + " bytes]";
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Trigger t && name.equals(t.name) && java.util.Arrays.equals(raw, t.raw)
                    && type == t.type;
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, java.util.Arrays.hashCode(raw), type);
        }
    }

    public Drop {
        Objects.requireNonNull(integrationId, "integrationId");
        Objects.requireNonNull(dropId, "dropId");
        Objects.requireNonNull(claimId, "claimId");
        files = List.copyOf(files);
        Objects.requireNonNull(trigger, "trigger");
        values = Map.copyOf(values);
        unlisted = List.copyOf(unlisted);
        missing = List.copyOf(missing);
    }

    /** The first extracted value of {@code name}, or null. */
    public String value(String name) {
        List<String> v = values.get(name);
        return v == null || v.isEmpty() ? null : v.get(0);
    }

    @Override
    public String toString() {
        return "Drop[" + dropId + ", " + files.size() + " file(s)]"; // never the values
    }
}
