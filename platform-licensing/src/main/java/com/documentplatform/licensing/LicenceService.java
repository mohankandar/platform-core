package com.documentplatform.licensing;

import com.documentplatform.licensing.usage.UsageAssessment;
import com.documentplatform.licensing.usage.UsageLedger;
import com.documentplatform.licensing.usage.UsageRecorder;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;

import static com.documentplatform.licensing.LicenceStore.Slot.CURRENT;
import static com.documentplatform.licensing.LicenceStore.Slot.WAITING;

/**
 * What an app uses for licensing: the state of the stored licence, whether intake is allowed, the kind (for the
 * "Test environment" marking), usage recording, and storing a new licence or a last-seen reset (the licence command).
 *
 * <p>Every read verifies the stored files again. The app always starts unless {@link #status()} throws a
 * {@link LicenceException} (no licence, unreadable, forged, tampered, wrong customer, unknown key ID). Expiry, grace
 * and a server clock behind are states and flags, never refusals.
 *
 * <p>Dates use the clock's effective time ({@link ClockReading}): turning the server clock back cannot extend a
 * licence. A renewal stored before its start date waits in its own slot; the current licence stays in force until the
 * effective date reaches the renewal's start date, and then the renewal takes over.
 */
public final class LicenceService {

    private static final System.Logger LOG = System.getLogger(LicenceService.class.getName());

    private final LicenceStore store;
    private final LicenceVerifier verifier;
    private final ClockGuard clock;
    private final UsageLedger ledger;
    private final String productCode;
    private final String tenantId;

    /**
     * @param productCode this app's product code, or null when the app does not check it
     * @param tenantId    this installation's tenant when configured, or null to take it from the stored licence
     */
    public LicenceService(LicenceStore store, LicenceVerifier verifier, ClockGuard clock, UsageLedger ledger,
                          String productCode, String tenantId) {
        this.store = Objects.requireNonNull(store, "store");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        if (productCode != null) {
            Licence.requireProductCode(productCode);
        }
        if (tenantId != null) {
            Licence.requireId(tenantId, "tenant ID");
        }
        this.productCode = productCode;
        this.tenantId = tenantId;
    }

    /**
     * The licence in force and its state. Promotes a waiting renewal whose start date has come.
     *
     * @throws LicenceException when the stored licence is refused (the app must not start)
     */
    public LicenceStatus status() {
        Optional<LicenceStore.Stored> currentRow = store.get(CURRENT);
        Optional<LicenceStore.Stored> waitingRow = store.get(WAITING);
        if (currentRow.isEmpty() && waitingRow.isEmpty()) {
            throw new LicenceException(LicenceRefusal.LICENCE_MISSING, "");
        }
        Licence current = currentRow.map(r -> verifier.verify(bytes(r), tenantId)).orElse(null);
        String tenant = tenantId != null ? tenantId : current == null ? null : current.tenantId();
        Licence waiting = waitingRow.map(r -> verifier.verify(bytes(r), tenant)).orElse(null);
        ClockReading reading = clock.read();
        LocalDate today = LocalDate.ofInstant(reading.effective(), ZoneOffset.UTC);
        if (waiting != null && (current == null || !today.isBefore(waiting.startsOn()))) {
            store.promoteWaiting(waiting.licenceId());
            LOG.log(System.Logger.Level.INFO, "Licence {0} v{1} took over from {2} on its start date",
                    waiting.licenceId(), waiting.version(), current == null ? "(none)" : current.licenceId());
            current = waiting;
            waiting = null;
        }
        LicenceStatus status = LicenceStatus.at(current, reading, waiting);
        if (reading.clockBehind()) {
            LOG.log(System.Logger.Level.WARNING, "Server clock behind: {0}", ClockReading.describe(reading.behind()));
        }
        return status;
    }

    /** Whether the licence lists this app's product (always true when no product code is configured). */
    public boolean productLicensed(LicenceStatus status) {
        return productCode == null || status.licence().products().contains(productCode);
    }

    /** Whether the app may take in new work now: the state allows it and the licence covers this product. */
    public boolean intakeAllowed() {
        LicenceStatus s = status();
        return s.intakeAllowed() && productLicensed(s);
    }

    public LicenceKind kind() {
        return status().licence().kind();
    }

    /** Whether the app shows its "Test environment" marking. */
    public boolean testEnvironment() {
        return kind().testEnvironment();
    }

    public Optional<String> productCode() {
        return Optional.ofNullable(productCode);
    }

    /** A usage recorder for the licence in force, dating records by the effective time. */
    public UsageRecorder usage() {
        return new UsageRecorder(status().licence(), ledger, effectiveClock());
    }

    /**
     * Records usage of this app's product (needs a configured product code). Never blocks.
     *
     * @param free true when the app does not count this reading (it is then never billable)
     */
    public UsageAssessment recordUsage(String slotId, AllowanceType type, long amount, boolean free) {
        if (productCode == null) {
            throw new IllegalStateException("No product code is configured for this app (platform.licensing.product-code)");
        }
        return usage().record(slotId, productCode, type, amount, free);
    }

    public UsageLedger ledger() {
        return ledger;
    }

    /** What storing a licence did. */
    public enum StoreResult {
        /** The first licence of this installation; it is current. */
        STORED_FIRST,
        /** It replaced the current licence (renewal or new version that starts now). */
        STORED_CURRENT,
        /** It starts later: stored as waiting; the current licence stays in force until then. */
        STORED_WAITING,
        /** This licence was already stored; nothing changed. */
        ALREADY_STORED
    }

    public record StoreOutcome(StoreResult result, Licence licence, LicenceStatus status) {
    }

    /**
     * Stores a licence file (first install or renewal). Verifies it first; refuses another customer's licence and an
     * older version. Idempotent: storing the same licence again changes nothing.
     *
     * @throws LicenceException with its code; nothing is stored then
     */
    public StoreOutcome store(byte[] file) {
        Optional<Licence> current = readable(CURRENT);
        Optional<Licence> waiting = readable(WAITING);
        String tenant = tenantId != null ? tenantId
                : current.map(Licence::tenantId).orElse(waiting.map(Licence::tenantId).orElse(null));
        Licence licence = verifier.verify(file, tenant);
        String text = new String(file, StandardCharsets.UTF_8);

        for (Optional<Licence> existing : java.util.List.of(current, waiting)) {
            if (existing.isPresent() && existing.get().licenceId().equals(licence.licenceId())) {
                return new StoreOutcome(StoreResult.ALREADY_STORED, licence, status());
            }
        }
        long newest = Math.max(current.map(Licence::version).orElse(0L), waiting.map(Licence::version).orElse(0L));
        if (licence.version() < newest) {
            throw new LicenceException(LicenceRefusal.LICENCE_OLDER_VERSION, "It is version " + licence.version()
                    + "; version " + newest + " is already stored.");
        }
        if (licence.version() == newest) {
            throw new LicenceException(LicenceRefusal.LICENCE_VERSION_CONFLICT, "Version " + newest
                    + " is already stored as a different licence; this one is " + licence.licenceId() + ".");
        }
        ClockReading reading = clock.read();
        Instant now = reading.effective();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        LicenceStore.Stored row = LicenceStore.Stored.of(text, licence, clock.serverTime());
        StoreResult result;
        if (current.isPresent() && today.isBefore(licence.startsOn())) {
            store.put(WAITING, row);
            result = StoreResult.STORED_WAITING;
        } else {
            store.put(CURRENT, row);
            if (waiting.isPresent()) {
                store.remove(WAITING); // older than this one (versions only go up)
            }
            result = current.isPresent() ? StoreResult.STORED_CURRENT : StoreResult.STORED_FIRST;
        }
        LOG.log(System.Logger.Level.INFO, "Licence {0} v{1} stored ({2})", licence.licenceId(), licence.version(),
                result);
        return new StoreOutcome(result, licence, status());
    }

    /** As {@link #store(byte[])}. */
    public StoreOutcome store(String file) {
        return store(file.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Applies a signed last-seen reset issued by our side for this installation.
     *
     * @throws ResetException with its code; nothing changes then
     */
    public LastSeenReset.Outcome applyReset(byte[] file) {
        String tenant = tenantId != null ? tenantId
                : readable(CURRENT).map(Licence::tenantId).orElse(readable(WAITING).map(Licence::tenantId).orElse(null));
        if (tenant == null) {
            throw new ResetException(ResetRefusal.RESET_WRONG_CUSTOMER,
                    "This installation has no licence yet, so the reset cannot be matched to it.");
        }
        LastSeenReset reset = LastSeenReset.verify(file, verifier.keys(), tenant);
        LastSeenReset.Outcome outcome = clock.apply(reset);
        LOG.log(System.Logger.Level.INFO, "Last-seen reset {0}: {1}", reset.resetId(), outcome);
        return outcome;
    }

    /** A clock that reads the effective time (and records it as seen). */
    public Clock effectiveClock() {
        return new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return clock.read().effective();
            }
        };
    }

    private Optional<Licence> readable(LicenceStore.Slot slot) {
        Optional<LicenceStore.Stored> row = store.get(slot);
        if (row.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(verifier.verify(bytes(row.get()), tenantId));
        } catch (LicenceException e) {
            return Optional.empty(); // a broken stored row is replaced by a good one; it never blocks storing
        }
    }

    private static byte[] bytes(LicenceStore.Stored row) {
        return row.file().getBytes(StandardCharsets.UTF_8);
    }
}
