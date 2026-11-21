package com.documentplatform.licensing.inmemory;

import com.documentplatform.licensing.NoticeLog;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** {@link NoticeLog} in memory, for tests. Shared by several "app instances" to show they never publish twice. */
public final class InMemoryNoticeLog implements NoticeLog {

    private final Map<String, Instant> claims = new ConcurrentHashMap<>();

    @Override
    public boolean claim(String noticeId, Instant at) {
        return claims.putIfAbsent(NoticeLog.requireId(noticeId), at) == null;
    }

    @Override
    public void release(String noticeId) {
        claims.remove(noticeId);
    }

    @Override
    public int purgeBefore(Instant before) {
        int[] n = {0};
        claims.entrySet().removeIf(e -> {
            boolean old = e.getValue().isBefore(before);
            if (old) {
                n[0]++;
            }
            return old;
        });
        return n[0];
    }

    public Set<String> claimed() {
        return Set.copyOf(claims.keySet());
    }
}
