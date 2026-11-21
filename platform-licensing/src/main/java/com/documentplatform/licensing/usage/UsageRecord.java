package com.documentplatform.licensing.usage;

import com.documentplatform.licensing.AllowanceType;

import java.time.Instant;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One ledger record: the entry, its position ({@code sequence}, from 1), its unit, and its link in the hash chain.
 * {@code hash = SHA-256(canonical JSON of every other field)}, hex; {@code previousHash} is the hash of the record
 * before, or {@link UsageChain#GENESIS} for the first. See {@link UsageChain}.
 */
public record UsageRecord(long sequence, String slotId, String product, AllowanceType type, YearMonth period,
                          long amount, String unit, boolean billable, Instant recordedAt, String previousHash,
                          String hash) {

    /** The fields covered by {@link #hash}, as a JSON object. */
    public Map<String, Object> hashedFields() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sequence", sequence);
        m.put("slotId", slotId);
        m.put("product", product);
        m.put("type", type.name());
        m.put("period", period.toString());
        m.put("amount", amount);
        m.put("unit", unit);
        m.put("billable", billable);
        m.put("recordedAt", recordedAt.toString());
        m.put("previousHash", previousHash);
        return m;
    }

    /** A copy with a different amount and the same hashes: what an edit in the store looks like. For tests. */
    public UsageRecord withAmount(long newAmount) {
        return new UsageRecord(sequence, slotId, product, type, period, newAmount, unit, billable, recordedAt,
                previousHash, hash);
    }
}
