package com.documentplatform.licensing;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * Where the installation keeps its signed licence files: the one in force ({@link Slot#CURRENT}) and a renewal stored
 * before its start date ({@link Slot#WAITING}). The files are kept exactly as received and verified on every read;
 * the other fields are copies for people looking at the table, never trusted. The Postgres one is
 * {@code jdbc.PostgresLicenceStore}; the testkit has an in-memory one.
 */
public interface LicenceStore {

    enum Slot { CURRENT, WAITING }

    /** One stored licence file. */
    record Stored(String file, String licenceId, long version, String tenantId, LocalDate startsOn, Instant storedAt) {
        public Stored {
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(licenceId, "licenceId");
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(startsOn, "startsOn");
            Objects.requireNonNull(storedAt, "storedAt");
        }

        public static Stored of(String file, Licence l, Instant storedAt) {
            return new Stored(file, l.licenceId(), l.version(), l.tenantId(), l.startsOn(), storedAt);
        }
    }

    Optional<Stored> get(Slot slot);

    /** Inserts or replaces the file in {@code slot}. */
    void put(Slot slot, Stored licence);

    void remove(Slot slot);

    /**
     * The highest licence version ever put in {@code slot} (0 if none), kept by the store itself and never lowered, so an
     * older signed licence written back is refused (P045, SEC-13).
     */
    long highestVersion(Slot slot);

    /**
     * In one step: the waiting licence becomes current and the waiting slot is emptied, provided the waiting licence
     * is still {@code licenceId} (another node may have promoted it already; then nothing happens).
     */
    void promoteWaiting(String licenceId);
}
