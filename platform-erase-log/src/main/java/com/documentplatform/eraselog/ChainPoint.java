package com.documentplatform.eraselog;

/**
 * A point in the erase log's chain: an entry's number and hash. {@link #START} (0, {@link EraseEntry#GENESIS}) is
 * before the first entry; a checkpoint is the last pruned entry's point.
 */
public record ChainPoint(long seq, String hash) {

    public static final ChainPoint START = new ChainPoint(0, EraseEntry.GENESIS);

    public ChainPoint {
        if (seq < 0) {
            throw new IllegalArgumentException("seq is not negative");
        }
        if (hash == null || !EraseEntry.HASH.matcher(hash).matches()) {
            throw new EraseLogException(EraseLogError.ERASE_LOG_FORMAT, "chain point " + seq + " has no valid hash");
        }
    }
}
