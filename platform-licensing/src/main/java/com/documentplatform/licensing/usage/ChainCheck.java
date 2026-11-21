package com.documentplatform.licensing.usage;

import java.util.Optional;

/**
 * The result of checking the ledger chain.
 *
 * @param brokenAtSequence the first record found wrong (0 when intact)
 */
public record ChainCheck(boolean intact, long recordsChecked, Problem problem, long brokenAtSequence, String message) {

    /** What is wrong with the chain. Stable codes. */
    public enum Problem {
        /** A record's content no longer matches its hash. */
        USAGE_CHAIN_RECORD_EDITED,
        /** A record does not point to the hash of the one before it. */
        USAGE_CHAIN_LINK_BROKEN,
        /** Sequence numbers are not 1, 2, 3, ... */
        USAGE_CHAIN_SEQUENCE_GAP
    }

    static ChainCheck intact(long checked) {
        return new ChainCheck(true, checked, null, 0, "The usage ledger chain is intact (" + checked + " records).");
    }

    static ChainCheck broken(Problem problem, long at, String message) {
        return new ChainCheck(false, at - 1, problem, at, problem.name() + ": " + message);
    }

    public Optional<Problem> problemCode() {
        return Optional.ofNullable(problem);
    }
}
