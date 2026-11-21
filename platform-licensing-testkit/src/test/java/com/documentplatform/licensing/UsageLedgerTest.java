package com.documentplatform.licensing;

import com.documentplatform.licensing.inmemory.InMemoryUsageLedger;
import com.documentplatform.licensing.usage.ChainCheck;
import com.documentplatform.licensing.usage.UsageChain;
import com.documentplatform.licensing.usage.UsageEntry;
import com.documentplatform.licensing.usage.UsageException;
import com.documentplatform.licensing.usage.UsageError;
import com.documentplatform.licensing.usage.UsageRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.YearMonth;

import static org.junit.jupiter.api.Assertions.*;

class UsageLedgerTest {

    private static final String SLOT = "ENT-0001";
    private static final String OTHER = "ENT-0002";
    private static final YearMonth NOV = YearMonth.of(2026, 11);
    private static final YearMonth DEC = YearMonth.of(2026, 12);
    private final InMemoryUsageLedger ledger = new InMemoryUsageLedger();

    private UsageRecord add(String slot, AllowanceType type, YearMonth period, long amount, boolean billable) {
        return ledger.append(new UsageEntry(slot, "PRODUCT_A", type, period, amount, billable,
                Instant.parse("2026-11-21T10:00:00Z")));
    }

    @Test
    void recordsAreChainedFromGenesis() {
        UsageRecord r1 = add(SLOT, AllowanceType.PAGES_PER_MONTH, NOV, 10, true);
        UsageRecord r2 = add(SLOT, AllowanceType.PAGES_PER_MONTH, NOV, 20, true);
        assertEquals(1, r1.sequence());
        assertEquals(UsageChain.GENESIS, r1.previousHash());
        assertEquals(r1.hash(), r2.previousHash());
        assertEquals("pages", r1.unit());
        assertTrue(r1.hash().matches("[0-9a-f]{64}"));
        assertTrue(ledger.verifyChain().intact());
        assertEquals(2, ledger.verifyChain().recordsChecked());
    }

    @Test
    void anEditedRecordBreaksTheChain() {
        add(SLOT, AllowanceType.PAGES_PER_MONTH, NOV, 10, true);
        UsageRecord r2 = add(SLOT, AllowanceType.PAGES_PER_MONTH, NOV, 20, true);
        add(SLOT, AllowanceType.PAGES_PER_MONTH, NOV, 30, true);
        ledger.overwriteForTest(2, r2.withAmount(2));
        ChainCheck c = ledger.verifyChain();
        assertFalse(c.intact());
        assertEquals(ChainCheck.Problem.USAGE_CHAIN_RECORD_EDITED, c.problem());
        assertEquals(2, c.brokenAtSequence());
        assertTrue(c.message().startsWith("USAGE_CHAIN_RECORD_EDITED: Record 2 "));
    }

    @Test
    void aFlippedBillableFlagBreaksTheChain() {
        UsageRecord r1 = add(SLOT, AllowanceType.PAGES_PER_MONTH, NOV, 10, true);
        ledger.overwriteForTest(1, new UsageRecord(1, r1.slotId(), r1.product(), r1.type(), r1.period(), r1.amount(),
                r1.unit(), false, r1.recordedAt(), r1.previousHash(), r1.hash()));
        assertEquals(ChainCheck.Problem.USAGE_CHAIN_RECORD_EDITED, ledger.verifyChain().problem());
    }

    @Test
    void aDeletedRecordBreaksTheChain() {
        add(SLOT, AllowanceType.PAGES_PER_MONTH, NOV, 10, true);
        add(SLOT, AllowanceType.PAGES_PER_MONTH, NOV, 20, true);
        add(SLOT, AllowanceType.PAGES_PER_MONTH, NOV, 30, true);
        ledger.deleteForTest(2);
        ChainCheck c = ledger.verifyChain();
        assertEquals(ChainCheck.Problem.USAGE_CHAIN_SEQUENCE_GAP, c.problem());
        assertEquals(2, c.brokenAtSequence());
    }

    @Test
    void aReChainedRecordThatSkipsItsPredecessorIsALinkBreak() {
        add(SLOT, AllowanceType.PAGES_PER_MONTH, NOV, 10, true);
        UsageRecord r2 = add(SLOT, AllowanceType.PAGES_PER_MONTH, NOV, 20, true);
        // Rewritten with a fresh, self-consistent hash but pointing at the wrong predecessor.
        UsageRecord unsealed = new UsageRecord(2, r2.slotId(), r2.product(), r2.type(), r2.period(), 5, r2.unit(),
                r2.billable(), r2.recordedAt(), UsageChain.GENESIS, "");
        ledger.overwriteForTest(2, new UsageRecord(2, r2.slotId(), r2.product(), r2.type(), r2.period(), 5, r2.unit(),
                r2.billable(), r2.recordedAt(), UsageChain.GENESIS, UsageChain.hashOf(unsealed)));
        ChainCheck c = ledger.verifyChain();
        assertEquals(ChainCheck.Problem.USAGE_CHAIN_LINK_BROKEN, c.problem());
        assertEquals(2, c.brokenAtSequence());
    }

    @Test
    void usagePerEntityPerMonthSumsPagesAndTakesThePeakOfReadings() {
        add(SLOT, AllowanceType.PAGES_PER_MONTH, NOV, 100, true);
        add(SLOT, AllowanceType.PAGES_PER_MONTH, NOV, 50, false);
        add(SLOT, AllowanceType.PAGES_PER_MONTH, DEC, 7, true);
        add(OTHER, AllowanceType.PAGES_PER_MONTH, NOV, 1000, true);
        add(SLOT, AllowanceType.CONCURRENT_SEATS, NOV, 4, true);
        add(SLOT, AllowanceType.CONCURRENT_SEATS, NOV, 9, true);
        add(SLOT, AllowanceType.CONCURRENT_SEATS, NOV, 6, true);
        assertEquals(150, ledger.usage(SLOT, "PRODUCT_A", AllowanceType.PAGES_PER_MONTH, NOV));
        assertEquals(100, ledger.billableUsage(SLOT, "PRODUCT_A", AllowanceType.PAGES_PER_MONTH, NOV));
        assertEquals(7, ledger.usage(SLOT, "PRODUCT_A", AllowanceType.PAGES_PER_MONTH, DEC));
        assertEquals(1000, ledger.usage(OTHER, "PRODUCT_A", AllowanceType.PAGES_PER_MONTH, NOV));
        assertEquals(9, ledger.usage(SLOT, "PRODUCT_A", AllowanceType.CONCURRENT_SEATS, NOV));
        assertEquals(0, ledger.usage(SLOT, "PRODUCT_A", AllowanceType.STORAGE, NOV));
    }

    @Test
    void aNegativeAmountIsRefused() {
        UsageException e = assertThrows(UsageException.class, () -> add(SLOT, AllowanceType.STORAGE, NOV, -1, true));
        assertEquals(UsageError.USAGE_NEGATIVE_AMOUNT, e.code());
    }
}
