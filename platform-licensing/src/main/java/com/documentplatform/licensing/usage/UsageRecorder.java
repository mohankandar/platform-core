package com.documentplatform.licensing.usage;

import com.documentplatform.licensing.Allowance;
import com.documentplatform.licensing.AllowanceType;
import com.documentplatform.licensing.EntitySlot;
import com.documentplatform.licensing.Licence;

import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Records usage at the app's meter points and reports where it stands. Never blocks: a reading over the allowance,
 * or for a product the slot has no allowance for, is recorded and reported as {@link UsageLevel#OVER_LIMIT}.
 *
 * <p>Billable flag: true only when the licence kind is billable ({@code PRODUCTION}) and the app does not mark the
 * reading free. Install and non-production usage is recorded but never billable.
 *
 * <p>Build a new recorder when a renewed licence is stored.
 */
public final class UsageRecorder {

    private final Licence licence;
    private final UsageLedger ledger;
    private final Clock clock;

    public UsageRecorder(Licence licence, UsageLedger ledger, Clock clock) {
        this.licence = Objects.requireNonNull(licence, "licence");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Records usage in the current month (UTC). */
    public UsageAssessment record(String slotId, String product, AllowanceType type, long amount, boolean free) {
        return record(slotId, product, type, currentMonth(), amount, free);
    }

    /** Records usage in {@code period}. */
    public UsageAssessment record(String slotId, String product, AllowanceType type, YearMonth period, long amount,
                                  boolean free) {
        slot(slotId);
        product(product);
        boolean billable = licence.kind().usageBillable() && !free;
        ledger.append(new UsageEntry(slotId, product, type, period, amount, billable, clock.instant()));
        return assess(slotId, product, type, period);
    }

    /** Where one allowance stands in {@code period}. */
    public UsageAssessment assess(String slotId, String product, AllowanceType type, YearMonth period) {
        EntitySlot slot = slot(slotId);
        product(product);
        long limit = slot.allowance(product, type).map(Allowance::amount).orElse(0L);
        long used = ledger.usage(slotId, product, type, period);
        return new UsageAssessment(slotId, product, type, period, used, limit, UsageLevel.of(used, limit));
    }

    /** Where every allowance of a slot stands in {@code period}. */
    public List<UsageAssessment> assess(String slotId, YearMonth period) {
        List<UsageAssessment> out = new ArrayList<>();
        for (Allowance a : slot(slotId).allowances()) {
            out.add(assess(slotId, a.product(), a.type(), period));
        }
        return out;
    }

    public YearMonth currentMonth() {
        return YearMonth.from(clock.instant().atZone(ZoneOffset.UTC));
    }

    private EntitySlot slot(String slotId) {
        return licence.slot(slotId).orElseThrow(() -> new UsageException(UsageError.USAGE_UNKNOWN_SLOT,
                "Slot " + slotId + " is not in licence " + licence.licenceId() + " v" + licence.version() + "."));
    }

    private void product(String product) {
        if (!licence.products().contains(product)) {
            throw new UsageException(UsageError.USAGE_UNKNOWN_PRODUCT,
                    "Product " + product + " is not in licence " + licence.licenceId() + " v" + licence.version() + ".");
        }
    }
}
