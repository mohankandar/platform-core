package com.documentplatform.licensing.inmemory;

import com.documentplatform.licensing.usage.ChainHead;
import com.documentplatform.licensing.usage.UsageChain;
import com.documentplatform.licensing.usage.UsageEntry;
import com.documentplatform.licensing.usage.UsageLedger;
import com.documentplatform.licensing.usage.UsageRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** A usage ledger in memory, for tests. Builds records from the head with {@link UsageChain#link} like every ledger. */
public final class InMemoryUsageLedger implements UsageLedger {

    private final List<UsageRecord> records = new ArrayList<>();
    private ChainHead head;

    @Override
    public synchronized UsageRecord append(UsageEntry entry) {
        UsageRecord record = UsageChain.link(head, entry);
        records.add(record);
        head = ChainHead.of(record);
        return record;
    }

    @Override
    public synchronized List<UsageRecord> records() {
        return List.copyOf(records);
    }

    @Override
    public synchronized Optional<ChainHead> head() {
        return Optional.ofNullable(head);
    }

    @Override
    public synchronized Snapshot snapshot() {
        return new Snapshot(List.copyOf(records), Optional.ofNullable(head));
    }

    /** Replaces a stored record as it is (no re-hashing): simulates an edit made directly in the store. */
    public synchronized void overwriteForTest(long sequence, UsageRecord replacement) {
        records.set(index(sequence), replacement);
    }

    /**
     * Changes a stored record's amount and keeps its hashes (no re-hashing): what an edit made directly in the store
     * looks like.
     */
    public synchronized void editAmountForTest(long sequence, long newAmount) {
        UsageRecord r = records.get(index(sequence));
        records.set(index(sequence), new UsageRecord(r.sequence(), r.slotId(), r.product(), r.type(), r.period(),
                newAmount, r.unit(), r.billable(), r.recordedAt(), r.previousHash(), r.hash()));
    }

    /** Removes a stored record (the head stays): simulates a row deleted directly in the store. */
    public synchronized void deleteForTest(long sequence) {
        records.remove(index(sequence));
    }

    private int index(long sequence) {
        for (int i = 0; i < records.size(); i++) {
            if (records.get(i).sequence() == sequence) {
                return i;
            }
        }
        throw new IllegalArgumentException("no record " + sequence);
    }

    /** All records, copied, for tests that build their own broken lists. */
    public synchronized List<UsageRecord> snapshotList() {
        return new ArrayList<>(records);
    }
}
