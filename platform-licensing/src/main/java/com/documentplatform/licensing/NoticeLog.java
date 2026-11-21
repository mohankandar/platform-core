package com.documentplatform.licensing;

import java.time.Instant;

/**
 * Which licensing notifications were already published, so a restart or a second app instance never publishes the
 * same one twice. A notice ID names what the notice is (for example "licence expiring, band 30, licence LIC-…, slot
 * ENT-…"), never message content.
 *
 * <p>Use: {@link #claim} before publishing; if publishing then fails, {@link #release} so the next check tries again.
 * Claims are atomic: when two instances claim the same ID at once, one wins.
 */
public interface NoticeLog {

    /** @return true if this ID was not claimed before (the caller publishes), false if it was */
    boolean claim(String noticeId, Instant at);

    /** Gives up a claim whose publishing failed. */
    void release(String noticeId);

    /** Forgets claims older than {@code before}; returns how many. */
    int purgeBefore(Instant before);

    /** Notice IDs: 1 to 200 printable ASCII characters, no spaces. */
    static String requireId(String noticeId) {
        if (noticeId == null || !noticeId.matches("[\\x21-\\x7e]{1,200}")) {
            throw new IllegalArgumentException("notice ID must be 1 to 200 printable characters without spaces");
        }
        return noticeId;
    }
}
