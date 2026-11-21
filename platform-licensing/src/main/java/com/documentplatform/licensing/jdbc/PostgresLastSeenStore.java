package com.documentplatform.licensing.jdbc;

import com.documentplatform.licensing.LastSeenStore;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** {@link LastSeenStore} on tables {@code lic_last_seen} and {@code lic_clock_reset}. DML only. */
public final class PostgresLastSeenStore implements LastSeenStore {

    private final DataSource ds;

    public PostgresLastSeenStore(DataSource ds) {
        this.ds = Objects.requireNonNull(ds, "ds");
    }

    @Override
    public Optional<Instant> lastSeen() {
        return Sql.run(ds, "read last seen", c -> {
            try (PreparedStatement ps = c.prepareStatement("select last_seen from lic_last_seen where id = 1");
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(Sql.instant(rs, "last_seen")) : Optional.empty();
            }
        });
    }

    @Override
    public void advanceTo(Instant seen) {
        Sql.run(ds, "advance last seen", c -> {
            // greatest(): never moves back, also when two nodes write at once.
            try (PreparedStatement ps = c.prepareStatement("insert into lic_last_seen (id, last_seen, updated_at) "
                    + "values (1, ?, now()) on conflict (id) do update set "
                    + "last_seen = greatest(lic_last_seen.last_seen, excluded.last_seen), updated_at = now() "
                    + "where excluded.last_seen > lic_last_seen.last_seen")) {
                ps.setObject(1, Sql.ts(seen));
                ps.executeUpdate();
                return null;
            }
        });
    }

    @Override
    public void resetTo(Instant to, String resetId) {
        Sql.inTransaction(ds, "apply last-seen reset", c -> {
            try (PreparedStatement used = c.prepareStatement(
                    "insert into lic_clock_reset (reset_id, reset_to) values (?, ?)");
                 PreparedStatement set = c.prepareStatement("insert into lic_last_seen (id, last_seen, updated_at) "
                         + "values (1, ?, now()) on conflict (id) do update set last_seen = excluded.last_seen, "
                         + "updated_at = now()")) {
                used.setString(1, resetId);
                used.setObject(2, Sql.ts(to));
                used.executeUpdate();
                set.setObject(1, Sql.ts(to));
                set.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public boolean resetApplied(String resetId) {
        return Sql.run(ds, "read applied resets", c -> {
            try (PreparedStatement ps = c.prepareStatement("select 1 from lic_clock_reset where reset_id = ?")) {
                ps.setString(1, resetId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }
}
