package com.documentplatform.licensing.jdbc;

import com.documentplatform.licensing.NoticeLog;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.Objects;

/** {@link NoticeLog} on table {@code lic_notice_sent}. DML only; a claim is one insert, so it is atomic. */
public final class PostgresNoticeLog implements NoticeLog {

    private final DataSource ds;

    public PostgresNoticeLog(DataSource ds) {
        this.ds = Objects.requireNonNull(ds, "ds");
    }

    @Override
    public boolean claim(String noticeId, Instant at) {
        NoticeLog.requireId(noticeId);
        return Sql.run(ds, "claim licensing notice", c -> {
            try (PreparedStatement ps = c.prepareStatement("insert into lic_notice_sent (notice_id, recorded_at) "
                    + "values (?, ?) on conflict (notice_id) do nothing")) {
                ps.setString(1, noticeId);
                ps.setObject(2, Sql.ts(at));
                return ps.executeUpdate() == 1;
            }
        });
    }

    @Override
    public void release(String noticeId) {
        Sql.run(ds, "release licensing notice", c -> {
            try (PreparedStatement ps = c.prepareStatement("delete from lic_notice_sent where notice_id = ?")) {
                ps.setString(1, noticeId);
                ps.executeUpdate();
                return null;
            }
        });
    }

    @Override
    public int purgeBefore(Instant before) {
        return Sql.run(ds, "purge licensing notices", c -> {
            try (PreparedStatement ps = c.prepareStatement("delete from lic_notice_sent where recorded_at < ?")) {
                ps.setObject(1, Sql.ts(before));
                return ps.executeUpdate();
            }
        });
    }
}
