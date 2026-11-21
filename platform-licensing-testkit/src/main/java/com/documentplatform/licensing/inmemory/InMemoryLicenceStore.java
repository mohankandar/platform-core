package com.documentplatform.licensing.inmemory;

import com.documentplatform.licensing.LicenceStore;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/** Stored licence files in memory, for tests. */
public final class InMemoryLicenceStore implements LicenceStore {

    private final Map<Slot, Stored> rows = new EnumMap<>(Slot.class);

    @Override
    public synchronized Optional<Stored> get(Slot slot) {
        return Optional.ofNullable(rows.get(slot));
    }

    @Override
    public synchronized void put(Slot slot, Stored licence) {
        rows.put(slot, licence);
    }

    @Override
    public synchronized void remove(Slot slot) {
        rows.remove(slot);
    }

    @Override
    public synchronized void promoteWaiting(String licenceId) {
        Stored waiting = rows.get(Slot.WAITING);
        if (waiting != null && waiting.licenceId().equals(licenceId)) {
            rows.put(Slot.CURRENT, waiting);
            rows.remove(Slot.WAITING);
        }
    }

    /** Replaces the stored file text as it is: simulates an edit made directly in the table. */
    public synchronized void overwriteFileForTest(Slot slot, String file) {
        Stored s = rows.get(slot);
        rows.put(slot, new Stored(file, s.licenceId(), s.version(), s.tenantId(), s.startsOn(), s.storedAt()));
    }
}
