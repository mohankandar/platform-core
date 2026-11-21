package com.documentplatform.licensing.inmemory;

import com.documentplatform.licensing.usage.UsageChain;
import com.documentplatform.licensing.usage.UsageEntry;
import com.documentplatform.licensing.usage.UsageLedger;
import com.documentplatform.licensing.usage.UsageRecord;

import java.util.ArrayList;
import java.util.List;

/** A usage ledger in memory, for tests. Builds records with {@link UsageChain#link} like every ledger. */
public final class InMemoryUsageLedger implements UsageLedger {

    private final List<UsageRecord> records = new ArrayList<>();

    @Override
    public synchronized UsageRecord append(UsageEntry entry) {
        UsageRecord last = records.isEmpty() ? null : records.get(records.size() - 1);
        UsageRecord record = UsageChain.link(last, entry);
        records.add(record);
        return record;
    }

    @Override
    public synchronized List<UsageRecord> records() {
        return List.copyOf(records);
    }

    /** Replaces a stored record as it is (no re-hashing): simulates an edit made directly in the store. */
    public synchronized void overwriteForTest(long sequence, UsageRecord replacement) {
        records.set(index(sequence), replacement);
    }

    /** Removes a stored record: simulates a row deleted directly in the store. */
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
    public synchronized List<UsageRecord> snapshot() {
        return new ArrayList<>(records);
    }
}
