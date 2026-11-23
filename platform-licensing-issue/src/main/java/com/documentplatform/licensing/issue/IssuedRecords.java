package com.documentplatform.licensing.issue;

import com.documentplatform.licensing.EntitySlot;
import com.documentplatform.licensing.Licence;
import com.documentplatform.licensing.json.Fields;
import com.documentplatform.licensing.json.Json;
import com.documentplatform.licensing.json.JsonException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The record of every licence issued: one canonical JSON object per line (JSON Lines), appended, never rewritten.
 * Holds IDs, customer, dates and the payload digest; never key material and never the signature.
 */
public final class IssuedRecords {

    /** One issued licence. */
    public record Entry(String licenceId, long version, String tenantId, String customer, String kind,
                        List<String> slotIds) {
    }

    private final Path file;
    private final List<Entry> entries;

    private IssuedRecords(Path file, List<Entry> entries) {
        this.file = file;
        this.entries = entries;
    }

    /** Reads the file; a missing file is an empty record. */
    public static IssuedRecords load(Path file) {
        List<Entry> entries = new ArrayList<>();
        if (Files.exists(file)) {
            List<String> lines;
            try {
                lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IssueException(IssueError.ISSUE_RECORDS_UNREADABLE, file + ": " + e.getClass().getSimpleName());
            }
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).isBlank()) {
                    continue;
                }
                try {
                    Fields f = Fields.of(Json.parse(lines.get(i)), "record");
                    List<String> slots = new ArrayList<>();
                    for (Object o : f.list("slotIds")) {
                        slots.add((String) o);
                    }
                    entries.add(new Entry(f.string("licenceId"), f.integer("version"), f.string("tenantId"),
                            f.string("customer"), f.string("kind"), slots));
                } catch (JsonException | ClassCastException e) {
                    throw new IssueException(IssueError.ISSUE_RECORDS_UNREADABLE,
                            file + " line " + (i + 1) + " is not a valid record (" + e.getMessage() + ").");
                }
            }
        }
        return new IssuedRecords(file, entries);
    }

    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    /** Every tenant, slot and licence ID in use. */
    public Set<String> allIds() {
        Set<String> ids = new HashSet<>();
        for (Entry e : entries) {
            ids.add(e.licenceId());
            ids.add(e.tenantId());
            ids.addAll(e.slotIds());
        }
        return ids;
    }

    public List<Entry> forTenant(String tenantId) {
        return entries.stream().filter(e -> e.tenantId().equals(tenantId)).toList();
    }

    /** Appends the record of a licence just signed. */
    public void append(Licence licence, String payloadSha256, Path licenceFile) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("licenceId", licence.licenceId());
        m.put("version", licence.version());
        m.put("tenantId", licence.tenantId());
        m.put("customer", licence.customer());
        m.put("kind", licence.kind().name());
        m.put("type", licence.type().name());
        m.put("products", licence.products());
        m.put("slotIds", licence.entitySlots().stream().map(EntitySlot::slotId).toList());
        m.put("startsOn", licence.startsOn().toString());
        licence.expiry().ifPresent(d -> m.put("expiresOn", d.toString()));
        licence.updatesUntilDate().ifPresent(d -> m.put("updatesUntil", d.toString()));
        m.put("deployment", licence.deployment().name());
        m.put("keyId", licence.keyId());
        m.put("issuedAt", licence.issuedAt().toString());
        m.put("payloadSha256", payloadSha256);
        m.put("licenceFile", licenceFile.getFileName().toString());
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, Json.canonical(m) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new IssueException(IssueError.ISSUE_RECORDS_UNWRITABLE, file + ": " + e.getClass().getSimpleName());
        }
        entries.add(new Entry(licence.licenceId(), licence.version(), licence.tenantId(), licence.customer(),
                licence.kind().name(), licence.entitySlots().stream().map(EntitySlot::slotId).toList()));
    }
}
